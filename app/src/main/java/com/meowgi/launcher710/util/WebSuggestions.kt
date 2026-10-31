package com.meowgi.launcher710.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Google query suggestions for searches that match nothing locally. Latest-only: each [request]
 * cancels the previous one (pending debounce or in-flight HTTP), and [onResult] only ever sees the
 * answer for the most recent query. Every failure — offline, timeout, bad JSON — yields no
 * suggestions rather than an error. Main thread only.
 */
class WebSuggestions(private val onResult: (query: String, suggestions: List<String>) -> Unit) {

    companion object {
        const val DEBOUNCE_MS = 200L
        const val MAX_SUGGESTIONS = 6
        private const val TIMEOUT_MS = 2500
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        fun buildUrl(query: String, language: String = Locale.getDefault().language): String =
            "https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8" +
                "&hl=" + URLEncoder.encode(language.ifEmpty { "en" }, "UTF-8") +
                "&q=" + URLEncoder.encode(query, "UTF-8")

        /**
         * Parses the `client=firefox` response, `["query", ["suggestion", ...], ...]`. Anything
         * malformed yields an empty list. Blank and duplicate (case-insensitive) entries are dropped.
         */
        fun parse(body: String, limit: Int = MAX_SUGGESTIONS): List<String> {
            val list = try {
                JSONArray(body).optJSONArray(1)
            } catch (_: JSONException) {
                null
            } ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<String>(minOf(limit, list.length()))
            for (i in 0 until list.length()) {
                if (out.size >= limit) break
                val s = (list.opt(i) as? String)?.trim()
                if (s.isNullOrEmpty()) continue
                if (seen.add(s.lowercase(Locale.ROOT))) out += s
            }
            return out
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    /** Query of the pending or in-flight request; repeats of it are not restarted. */
    private var requested: String? = null
    private val connection = AtomicReference<HttpURLConnection?>()

    fun request(query: String) {
        if (query == requested && job?.isActive == true) return
        cancel()
        requested = query
        job = scope.launch {
            delay(DEBOUNCE_MS)
            val suggestions = withContext(Dispatchers.IO) { fetch(query) }
            // A request() or cancel() since then cancelled this job, so withContext threw instead.
            onResult(query, suggestions)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        requested = null
        // Aborts a blocked read; done off the main thread since it may close a socket.
        connection.getAndSet(null)?.let { conn -> scope.launch(Dispatchers.IO) { conn.disconnect() } }
    }

    private fun fetch(query: String): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(buildUrl(query)).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            connection.set(conn)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val charset = conn.contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { charset(it) }.getOrNull() }
                ?: Charsets.UTF_8
            val bytes = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (buf.size() < MAX_RESPONSE_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            parse(String(bytes, charset))
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.compareAndSet(conn, null)
            conn?.disconnect()
        }
    }
}
