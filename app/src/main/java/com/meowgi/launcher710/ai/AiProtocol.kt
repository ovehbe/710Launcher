package com.meowgi.launcher710.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** One step of a streamed answer. */
sealed class AiEvent {
    data class Token(val text: String) : AiEvent()
    object Done : AiEvent()
    data class Failure(val message: String, val kind: AiFailure = AiFailure.SERVER) : AiEvent()
}

enum class AiFailure { CONFIG, AUTH, NETWORK, SERVER }

class AiException(message: String, val kind: AiFailure) : Exception(message)

/**
 * Incremental Server-Sent Events parser: [feed] takes arbitrary chunks (lines may be split
 * anywhere) and returns the `data` of each completed event, with its `event` name.
 *
 * Events end at a blank line, per the spec. Some OpenAI-compatible servers omit the blank line
 * between `data:` lines; a new `data:` line therefore also ends the pending event when that
 * pending data is already complete (`[DONE]` or a whole JSON value).
 */
class SseParser {
    data class Event(val data: String, val name: String? = null)

    private val line = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var name: String? = null
    private var lastWasCr = false

    fun feed(chunk: CharSequence): List<Event> {
        val out = ArrayList<Event>(2)
        for (c in chunk) {
            if (c == '\n' && lastWasCr) { lastWasCr = false; continue }
            lastWasCr = c == '\r'
            if (c == '\n' || c == '\r') {
                onLine(line.toString(), out)
                line.setLength(0)
            } else {
                line.append(c)
            }
        }
        return out
    }

    /** Ends the stream: a final event without its trailing blank line is still delivered. */
    fun finish(): List<Event> {
        val out = ArrayList<Event>(1)
        if (line.isNotEmpty()) { onLine(line.toString(), out); line.setLength(0) }
        dispatch(out)
        return out
    }

    private fun onLine(l: String, out: MutableList<Event>) {
        if (l.isEmpty()) { dispatch(out); return }
        if (l.startsWith(":")) return
        val field = l.substringBefore(':')
        var value = if (':' in l) l.substringAfter(':') else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                if (hasData && isComplete(data.toString())) dispatch(out)
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> name = value
        }
    }

    private fun dispatch(out: MutableList<Event>) {
        if (hasData) out += Event(data.toString(), name)
        data.setLength(0)
        hasData = false
        name = null
    }

    private fun isComplete(s: String): Boolean {
        val t = s.trim()
        if (t == AiProtocol.DONE) return true
        if (!(t.startsWith("{") || t.startsWith("["))) return false
        return try {
            val tok = JSONTokener(t)
            tok.nextValue()
            !tok.more()
        } catch (_: JSONException) {
            false
        }
    }
}

object AiProtocol {
    const val DONE = "[DONE]"
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isValidBaseUrl(raw: String): Boolean {
        val u = normalizeBaseUrl(raw).lowercase(Locale.ROOT)
        return (u.startsWith("https://") || u.startsWith("http://")) && u.substringAfter("://").isNotEmpty() && ' ' !in u
    }

    fun chatUrl(base: String) = normalizeBaseUrl(base) + "/chat/completions"
    fun modelsUrl(base: String) = normalizeBaseUrl(base) + "/models"

    fun chatBody(model: String, systemPrompt: String, query: String, stream: Boolean = true): String {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt.trim()))
        messages.put(JSONObject().put("role", "user").put("content", query))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", stream)
            .toString()
    }

    /**
     * Maps one SSE event to an [AiEvent], or null for events that carry no text (role-only deltas,
     * usage chunks, keep-alives).
     */
    fun parseEvent(event: SseParser.Event): AiEvent? {
        val data = event.data.trim()
        if (data == DONE) return AiEvent.Done
        if (data.isEmpty()) return null
        val json = try {
            JSONObject(data)
        } catch (_: JSONException) {
            return if (event.name == "error") AiEvent.Failure(data.take(200)) else null
        }
        errorMessage(json)?.let { return AiEvent.Failure(it) }
        if (event.name == "error") return AiEvent.Failure(json.optString("message").ifEmpty { data.take(200) })
        return contentOf(json)?.takeIf { it.isNotEmpty() }?.let { AiEvent.Token(it) }
    }

    /** Text of a streamed chunk (`delta.content`) or of a whole, non-streamed completion. */
    fun contentOf(json: JSONObject): String? {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        choice.optJSONObject("delta")?.let { d -> return d.stringOrNull("content") }
        choice.optJSONObject("message")?.let { m -> return m.stringOrNull("content") }
        return choice.stringOrNull("text")
    }

    /** The answer of a server that ignored `stream: true` and sent one JSON completion. */
    fun parseCompletion(body: String): AiEvent {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return AiEvent.Failure("The AI server sent an unreadable answer.")
        }
        errorMessage(json)?.let { return AiEvent.Failure(it) }
        val text = contentOf(json) ?: return AiEvent.Failure("The AI server's answer had no text.")
        return AiEvent.Token(text)
    }

    /** `{"error": {"message": …}}` or `{"error": "…"}`, as OpenAI-compatible servers send. */
    fun errorMessage(json: JSONObject): String? {
        if (!json.has("error")) return null
        val e = json.opt("error")
        return when (e) {
            is JSONObject -> e.optString("message").ifBlank { e.optString("type") }.ifBlank { "Unknown error" }
            is String -> e.ifBlank { "Unknown error" }
            else -> if (e == JSONObject.NULL) null else e.toString()
        }
    }

    /**
     * Model ids from GET /models: OpenAI's `{"data": [{"id": …}]}`, plus the `{"models": […]}`
     * and bare-array shapes some compatible servers use. Sorted and de-duplicated.
     */
    fun parseModels(body: String): List<String> {
        val root = try {
            JSONTokener(body.trim()).nextValue()
        } catch (_: JSONException) {
            throw AiException("The server's model list isn't valid JSON.", AiFailure.SERVER)
        }
        if (root is JSONObject) errorMessage(root)?.let { throw AiException(it, AiFailure.SERVER) }
        val list: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("models")
                ?: throw AiException("The server's answer has no model list.", AiFailure.SERVER)
            else -> throw AiException("The server's answer has no model list.", AiFailure.SERVER)
        }
        val ids = LinkedHashSet<String>()
        for (i in 0 until list.length()) {
            val id = when (val m = list.opt(i)) {
                is String -> m
                is JSONObject -> m.stringOrNull("id") ?: m.stringOrNull("name") ?: m.stringOrNull("model")
                else -> null
            }?.trim()
            if (!id.isNullOrEmpty()) ids += id
        }
        return ids.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** A short message for a failed HTTP call; [body] is the (possibly JSON) error body. */
    fun httpFailure(code: Int, body: String?): AiException {
        val detail = body?.let {
            try { errorMessage(JSONObject(it)) } catch (_: JSONException) { null }
        }?.take(160)
        val suffix = detail?.let { ": $it" } ?: ""
        return when (code) {
            401, 403 -> AiException("The AI server rejected the API key ($code)$suffix", AiFailure.AUTH)
            404 -> AiException("Not found (404): check the base URL and model$suffix", AiFailure.CONFIG)
            429 -> AiException("Rate limited or out of quota (429)$suffix", AiFailure.SERVER)
            else -> AiException("AI server error $code$suffix", AiFailure.SERVER)
        }
    }

    /** Never shows the key, even when a server echoes it back in an error. */
    fun redact(message: String, apiKey: String?): String =
        if (apiKey.isNullOrBlank() || apiKey.length < 4) message else message.replace(apiKey, "•••")

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) opt(key) as? String else null
}
