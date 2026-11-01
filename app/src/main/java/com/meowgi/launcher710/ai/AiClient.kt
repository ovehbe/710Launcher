package com.meowgi.launcher710.ai

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Abort handle for a blocking call; [cancel] may be called from any thread except main (it can close a socket). */
class AiCall {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    val isCancelled get() = cancelled.get()

    internal fun attach(conn: HttpURLConnection) {
        connection.set(conn)
        if (cancelled.get()) conn.disconnect()
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) connection.getAndSet(null)?.disconnect()
    }
}

/** Blocking OpenAI-compatible HTTP calls. Run off the main thread. The API key is never logged. */
object AiClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_ERROR_BYTES = 16 * 1024
    private const val MAX_MODELS_BYTES = 2 * 1024 * 1024

    /**
     * POSTs a streamed chat completion and reports each [AiEvent] as it arrives. Always ends with
     * exactly one [AiEvent.Done] or [AiEvent.Failure], unless [call] was cancelled.
     */
    fun streamChat(config: AiConfig, query: String, call: AiCall, onEvent: (AiEvent) -> Unit) {
        var finished = false
        val emit = { e: AiEvent ->
            if (!finished && !call.isCancelled) {
                if (e is AiEvent.Done || e is AiEvent.Failure) finished = true
                onEvent(if (e is AiEvent.Failure) e.copy(message = AiProtocol.redact(e.message, config.apiKey)) else e)
            }
        }
        try {
            val conn = open(AiProtocol.chatUrl(config.baseUrl), config.apiKey, call).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream, application/json")
            }
            conn.outputStream.use { it.write(AiProtocol.chatBody(config.model, config.systemPrompt, query).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw AiProtocol.httpFailure(code, readCapped(conn.errorStream, MAX_ERROR_BYTES))
            val type = conn.contentType.orEmpty().lowercase()
            if (!type.contains("event-stream") && type.contains("json")) {
                emit(AiProtocol.parseCompletion(readCapped(conn.inputStream, MAX_MODELS_BYTES).orEmpty()))
                emit(AiEvent.Done)
                return
            }
            val parser = SseParser()
            InputStreamReader(conn.inputStream, Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                while (!finished && !call.isCancelled) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    for (event in parser.feed(String(buf, 0, n))) AiProtocol.parseEvent(event)?.let(emit)
                }
                for (event in parser.finish()) AiProtocol.parseEvent(event)?.let(emit)
            }
            emit(AiEvent.Done)
        } catch (e: AiException) {
            emit(AiEvent.Failure(e.message.orEmpty(), e.kind))
        } catch (e: IOException) {
            emit(AiEvent.Failure(networkMessage(e), AiFailure.NETWORK))
        } catch (e: RuntimeException) {
            emit(AiEvent.Failure("AI request failed: ${e.javaClass.simpleName}", AiFailure.NETWORK))
        }
    }

    /** GET {base}/models. Throws [AiException] with a user-facing message. */
    fun fetchModels(baseUrl: String, apiKey: String, call: AiCall = AiCall()): List<String> {
        try {
            val conn = open(AiProtocol.modelsUrl(baseUrl), apiKey, call)
            val code = conn.responseCode
            if (code !in 200..299) throw AiProtocol.httpFailure(code, readCapped(conn.errorStream, MAX_ERROR_BYTES))
            return AiProtocol.parseModels(readCapped(conn.inputStream, MAX_MODELS_BYTES).orEmpty())
        } catch (e: AiException) {
            throw AiException(AiProtocol.redact(e.message.orEmpty(), apiKey), e.kind)
        } catch (e: IOException) {
            throw AiException(networkMessage(e), AiFailure.NETWORK)
        }
    }

    private fun open(url: String, apiKey: String, call: AiCall): HttpURLConnection {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("Invalid base URL.", AiFailure.CONFIG)
        }
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        call.attach(conn)
        return conn
    }

    private fun readCapped(input: InputStream?, max: Int): String? = input?.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = s.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "Couldn't find the AI server. Check the base URL and your connection."
        is java.net.SocketTimeoutException -> "The AI server didn't answer in time."
        is javax.net.ssl.SSLException -> "Secure connection to the AI server failed."
        else -> "Couldn't reach the AI server. Check the base URL and your connection."
    }
}
