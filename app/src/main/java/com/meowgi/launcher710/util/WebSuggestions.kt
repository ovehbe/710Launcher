package com.meowgi.launcher710.util

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Google query suggestions for searches that match nothing locally. Main thread only.
 *
 * Latest-only: [onResult] is only ever called for the most recent [request], and nothing after
 * [cancel]. A superseded HTTP request is left to finish rather than aborted, so its keep-alive
 * connection goes back to the pool and its answer still lands in the cache.
 *
 * Speed: a request fires at once unless another started within [DEBOUNCE_MS]. Answers are kept
 * in a small LRU cache. A cached answer for the same query is shown without a request; one for
 * a shorter prefix is filtered and shown while the request runs. [prewarm] opens the connection
 * (DNS, TCP, TLS) when search opens.
 *
 * Every failure (offline, timeout, bad JSON) yields no suggestions rather than an error.
 * Timings go to logcat with `adb shell setprop log.tag.WebSuggestions DEBUG`.
 */
class WebSuggestions(
    main: CoroutineDispatcher = Dispatchers.Main,
    private val onResult: (query: String, suggestions: List<String>) -> Unit
) {

    companion object {
        const val DEBOUNCE_MS = 60L
        const val MAX_SUGGESTIONS = 6
        const val CACHE_SIZE = 64
        const val CACHE_TTL_MS = 10 * 60_000L
        /** Comfortably inside the 5-minute idle limit of Android's HTTP connection pool. */
        const val PREWARM_IDLE_MS = 60_000L
        private const val TIMEOUT_MS = 2500
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val TAG = "WebSuggestions"

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

        /**
         * Whether a query gets web suggestions. Only text matches count as "something matched":
         * keypad-digit matches ("sjsj" → digits "44" inside a contact's number or the "710"
         * alias) hit almost every query and must not suppress suggestions. A dialable query gets
         * the dial row instead.
         */
        fun shouldRequest(enabled: Boolean, allowed: Boolean, query: String, dialable: Boolean, hasTextMatch: Boolean): Boolean =
            enabled && allowed && query.isNotBlank() && !dialable && !hasTextMatch

        /** Suggestions for a shorter query that still complete [query]. */
        fun narrow(suggestions: List<String>, query: String): List<String> =
            suggestions.filter { it.startsWith(query, ignoreCase = true) }
    }

    /** Replaces the HTTP fetch; runs on [Dispatchers.IO]. Null means the request failed. */
    internal var fetcher: ((String) -> List<String>?)? = null
    /** Replaces the clock (ms) for debounce, cache expiry and prewarm throttling. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val trace: ((String) -> Unit)? =
        if (runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)) { msg -> Log.d(TAG, msg) } else null

    private val scope = CoroutineScope(SupervisorJob() + main)
    private var debounceJob: Job? = null
    private var current: String? = null
    private var currentAt = 0L
    /** The network (or exact cache) answer for [current] has been delivered. */
    private var answered = false
    /** A narrowed prefix answer for [current] has been delivered. */
    private var narrowed = false
    private var lastFetchAt = Long.MIN_VALUE / 2
    private var lastNetworkAt = Long.MIN_VALUE / 2
    private val cache = object : LinkedHashMap<String, Pair<Long, List<String>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<String>>>) = size > CACHE_SIZE
    }

    fun request(query: String) {
        if (query == current) return
        debounceJob?.cancel()
        current = query
        currentAt = clock()
        answered = false
        narrowed = false

        cached(query)?.let { hit ->
            answered = true
            deliver(query, hit, "cache")
            return
        }
        cachedPrefix(query)?.let { narrowed = true; deliver(query, it, "prefix") }

        val wait = if (currentAt - lastFetchAt >= DEBOUNCE_MS) 0L else DEBOUNCE_MS
        debounceJob = scope.launch {
            if (wait > 0) delay(wait)
            startFetch(query)
        }
    }

    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
        current = null
    }

    /** Opens a connection ahead of the first keystroke unless one was used recently. */
    fun prewarm() {
        val now = clock()
        if (now - lastNetworkAt < PREWARM_IDLE_MS) return
        lastNetworkAt = now
        scope.launch(Dispatchers.IO) {
            val t = System.nanoTime()
            val ok = fetchVia("") != null
            trace?.invoke("prewarm ok=$ok ${(System.nanoTime() - t) / 1_000_000}ms")
        }
    }

    private fun startFetch(query: String) {
        val startedAt = clock()
        lastFetchAt = startedAt
        lastNetworkAt = startedAt
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchVia(query) }
            if (result != null) cache[key(query)] = clock() to result
            onFetched(query, result, startedAt)
        }
    }

    private fun fetchVia(query: String): List<String>? = fetcher.let { if (it != null) it(query) else fetch(query) }

    private fun onFetched(query: String, result: List<String>?, startedAt: Long) {
        val cur = current ?: return
        if (answered) return
        if (cur == query) {
            answered = true
            trace?.invoke("q=\"$query\" debounce=${startedAt - currentAt}ms request=${clock() - startedAt}ms")
            // A failed request keeps a narrowed prefix answer on screen instead of clearing it.
            if (result != null || !narrowed) deliver(cur, result.orEmpty(), "network")
        } else if (result != null && cur.startsWith(query, ignoreCase = true)) {
            // A superseded request for a prefix came back before the current one.
            val hit = narrow(result, cur)
            if (hit.isNotEmpty()) { narrowed = true; deliver(cur, hit, "late prefix") }
        }
    }

    private fun deliver(query: String, suggestions: List<String>, source: String) {
        val t = System.nanoTime()
        onResult(query, suggestions)
        trace?.invoke("q=\"$query\" source=$source shown=${suggestions.size} at=${clock() - currentAt}ms ui=${(System.nanoTime() - t) / 1000}µs")
    }

    private fun key(query: String) = query.lowercase(Locale.ROOT)

    private fun cached(query: String): List<String>? {
        val k = key(query)
        val (at, list) = cache[k] ?: return null
        if (clock() - at > CACHE_TTL_MS) { cache.remove(k); return null }
        return list
    }

    /** The longest fresh cached prefix's suggestions that still complete [query], if any. */
    private fun cachedPrefix(query: String): List<String>? {
        for (end in query.length - 1 downTo 1) {
            val hit = cached(query.substring(0, end)) ?: continue
            return narrow(hit, query).takeIf { it.isNotEmpty() } ?: return null
        }
        return null
    }

    /**
     * Never calls disconnect() after a complete read: that would close the socket instead of
     * returning it to the keep-alive pool for the next keystroke.
     */
    private fun fetch(query: String): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            val t0 = System.nanoTime()
            conn = (URL(buildUrl(query)).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            val t1 = System.nanoTime()
            val charset = conn.contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { charset(it) }.getOrNull() }
                ?: Charsets.UTF_8
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (buf.size() < MAX_RESPONSE_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            val t2 = System.nanoTime()
            val parsed = parse(String(bytes, charset))
            trace?.invoke("fetch q=\"$query\" headers=${(t1 - t0) / 1_000_000}ms body=${(t2 - t1) / 1_000_000}ms parse=${(System.nanoTime() - t2) / 1000}µs")
            parsed
        } catch (_: Exception) {
            conn?.disconnect()
            null
        }
    }
}
