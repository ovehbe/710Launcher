package com.meowgi.launcher710.util

import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Request scheduling, caching and stale-drop of [WebSuggestions], with a fake fetcher. */
class WebSuggestionsRequestTest {

    private val exec = Executors.newSingleThreadExecutor()
    private val results = CopyOnWriteArrayList<Pair<String, List<String>>>()
    private val fetched = CopyOnWriteArrayList<String>()
    private val answers = ConcurrentHashMap<String, List<String>>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var now = 0L
    private val ws = WebSuggestions(exec.asCoroutineDispatcher()) { q, s -> results += q to s }.apply {
        clock = { now }
        fetcher = { q ->
            fetched += q
            gates[q]?.await(5, TimeUnit.SECONDS)
            if (q.startsWith("fail")) null else answers[q] ?: listOf("$q one", "$q two")
        }
    }

    @After
    fun tearDown() {
        exec.shutdownNow()
    }

    private fun onMain(block: WebSuggestions.() -> Unit) = exec.submit { ws.block() }.get()

    /** Requests on the main thread and returns what was shown synchronously, before any fetch. */
    private fun request(q: String): List<Pair<String, List<String>>> {
        var shown: List<Pair<String, List<String>>> = emptyList()
        // Snapshot inside the main-thread task: once it returns, the fetch it scheduled can
        // deliver at any moment, and that is not "shown synchronously".
        onMain {
            val before = results.size
            request(q)
            shown = results.drop(before)
        }
        return shown
    }

    private fun awaitResult(q: String, count: Int = 1) {
        val end = System.currentTimeMillis() + 3000
        while (results.count { it.first == q } < count) {
            check(System.currentTimeMillis() < end) { "no result for $q; got $results" }
            Thread.sleep(5)
        }
        exec.submit { }.get()
    }

    private fun settle() {
        Thread.sleep(WebSuggestions.DEBOUNCE_MS + 100)
        exec.submit { }.get()
    }

    @Test
    fun firstKeystrokeFiresAtOnceAndBurstsAreDebounced() {
        request("a")
        awaitResult("a")
        assertEquals(listOf("a"), fetched.toList())

        // Within the debounce window of the last fetch: only the latest query is fetched.
        request("ab")
        request("abc")
        awaitResult("abc")
        settle()
        assertEquals(listOf("a", "abc"), fetched.toList())

        now += WebSuggestions.DEBOUNCE_MS
        request("abcd")
        awaitResult("abcd")
        assertEquals(listOf("a", "abc", "abcd"), fetched.toList())
    }

    @Test
    fun cachedQueryIsShownInstantlyWithoutARequest() {
        request("weather")
        awaitResult("weather")
        now += 1000
        request("news")
        awaitResult("news")

        now += 1000
        assertEquals(listOf("Weather" to listOf("weather one", "weather two")), request("Weather"))
        settle()
        assertEquals(listOf("weather", "news"), fetched.toList())
    }

    @Test
    fun cacheEntriesExpire() {
        request("weather")
        awaitResult("weather")
        request("x")
        awaitResult("x")
        now += WebSuggestions.CACHE_TTL_MS + 1
        request("weather").let { assertTrue("expired: $it fetched=$fetched results=$results", it.isEmpty()) }
        awaitResult("weather", count = 2)
        assertEquals(2, fetched.count { it == "weather" })
    }

    @Test
    fun cacheIsBounded() {
        for (i in 0..WebSuggestions.CACHE_SIZE) {
            now += 1000
            request("q$i")
            awaitResult("q$i")
        }
        now += 1000
        request("q0").let { assertTrue("eldest entry evicted: $it fetched=$fetched", it.isEmpty()) }
        awaitResult("q0", count = 2)
        now += 1000
        assertEquals(1, request("q${WebSuggestions.CACHE_SIZE}").size)
    }

    @Test
    fun cachedPrefixIsNarrowedAndShownWhileTheRequestRuns() {
        answers["we"] = listOf("weather", "web", "Wealth", "wells")
        request("we")
        awaitResult("we")
        now += 1000

        gates["wea"] = CountDownLatch(1)
        assertEquals(listOf("wea" to listOf("weather", "Wealth")), request("wea"))
        gates.getValue("wea").countDown()
        awaitResult("wea", count = 2)
        assertEquals("wea" to listOf("wea one", "wea two"), results.last())
    }

    @Test
    fun prefixWithNoCompletionsShowsNothingEarly() {
        answers["we"] = listOf("web")
        request("we")
        awaitResult("we")
        now += 1000
        assertTrue(request("wea").isEmpty())
        awaitResult("wea")
    }

    @Test
    fun supersededPrefixAnswerFillsInWhileTheCurrentRequestIsInFlight() {
        answers["pi"] = listOf("pizza hut", "pixel", "pizza near me")
        gates["pi"] = CountDownLatch(1)
        gates["piz"] = CountDownLatch(1)
        request("pi")
        now += 1000
        request("piz")
        Thread.sleep(50)
        gates.getValue("pi").countDown()
        awaitResult("piz")
        assertEquals(listOf("piz" to listOf("pizza hut", "pizza near me")), results.toList())

        gates.getValue("piz").countDown()
        awaitResult("piz", count = 2)
        assertEquals("piz" to listOf("piz one", "piz two"), results.last())
        assertTrue("never shown for a stale query", results.none { it.first == "pi" })
    }

    @Test
    fun staleAndCancelledAnswersAreDropped() {
        gates["a"] = CountDownLatch(1)
        request("a")
        now += 1000
        request("b")
        awaitResult("b")
        gates.getValue("a").countDown()
        settle()
        assertTrue(results.none { it.first == "a" })

        gates["c"] = CountDownLatch(1)
        now += 1000
        request("c")
        onMain { cancel() }
        gates.getValue("c").countDown()
        settle()
        assertTrue(results.none { it.first == "c" })

        // Superseded answers still fill the cache.
        now += 1000
        assertEquals(listOf("a" to listOf("a one", "a two")), request("a"))
    }

    @Test
    fun repeatedRequestForTheSameQueryIsANoOp() {
        gates["a"] = CountDownLatch(1)
        request("a")
        request("a")
        gates.getValue("a").countDown()
        awaitResult("a")
        settle()
        assertEquals(listOf("a"), fetched.toList())
        assertEquals(1, results.size)
    }

    @Test
    fun failuresShowNothingAndAreNotCached() {
        request("failx")
        awaitResult("failx")
        assertEquals("failx" to emptyList<String>(), results.last())
        now += 1000
        request("other")
        awaitResult("other")
        now += 1000
        assertTrue(request("failx").isEmpty())
        awaitResult("failx", count = 2)
        assertEquals(2, fetched.count { it == "failx" })
    }

    @Test
    fun failureKeepsANarrowedPrefixAnswer() {
        answers["fai"] = listOf("fail army", "fairy")
        request("fai")
        awaitResult("fai")
        now += 1000
        assertEquals(listOf("fail" to listOf("fail army")), request("fail"))
        settle()
        Thread.sleep(100)
        assertEquals("fail" to listOf("fail army"), results.last())
    }

    @Test
    fun prewarmIsThrottled() {
        onMain { prewarm() }
        settle()
        assertEquals(listOf(""), fetched.toList())
        now += WebSuggestions.PREWARM_IDLE_MS - 1
        onMain { prewarm() }
        settle()
        assertEquals(1, fetched.size)

        // A real request counts as recent network use too.
        now += 10
        request("q")
        awaitResult("q")
        now += WebSuggestions.PREWARM_IDLE_MS - 1
        onMain { prewarm() }
        settle()
        assertEquals(listOf("", "q"), fetched.toList())
        now += 1
        onMain { prewarm() }
        settle()
        assertEquals(listOf("", "q", ""), fetched.toList())
    }
}
