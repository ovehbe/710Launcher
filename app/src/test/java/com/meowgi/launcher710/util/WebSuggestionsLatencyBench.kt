package com.meowgi.launcher710.util

import com.squareup.okhttp.ConnectionPool
import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.OkUrlFactory
import com.squareup.okhttp.Protocol
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Live-network latency bench for [WebSuggestions]; skipped unless `SUGGEST_BENCH=1`.
 *
 * Requests go through OkHttp 2.7.5's HttpURLConnection (what Android's is forked from), so
 * pooling and disconnect() behave as on a device, and through a local CONNECT proxy that adds
 * `SUGGEST_BENCH_RTT` ms (default 0 and 60) of round-trip time to model a phone link.
 * OkHttp 2.7.5 only speaks TLS 1.2, so each new connection costs one RTT more here than with
 * TLS 1.3 on Android 10+.
 *
 * Run: `SUGGEST_BENCH=1 ./gradlew testDebugUnitTest --tests '*WebSuggestionsLatencyBench*' -i`
 */
class WebSuggestionsLatencyBench {

    @Test
    fun bench() {
        assumeTrue(System.getenv("SUGGEST_BENCH") != null)
        val rtts = System.getenv("SUGGEST_BENCH_RTT")?.split(',')?.map { it.trim().toInt() } ?: listOf(0, 60)
        for (rtt in rtts) {
            rttMs.set(rtt)
            println("\n=== simulated RTT +$rtt ms ===")
            phases()
            scenarios()
        }
    }

    private fun phases() {
        fun once(q: String): Triple<Long, Long, Long> {
            val t0 = System.nanoTime()
            val conn = URL(WebSuggestions.buildUrl(q)).openConnection() as HttpURLConnection
            conn.responseCode
            val t1 = System.nanoTime()
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val t2 = System.nanoTime()
            WebSuggestions.parse(body)
            val t3 = System.nanoTime()
            return Triple(t1 - t0, t2 - t1, t3 - t2)
        }
        val cold = (1..5).map { client.connectionPool.evictAll(); once("cold$it") }
        once("warmup")
        val warm = (1..5).map { once("warm$it") }
        fun med(xs: List<Long>) = xs.sorted()[xs.size / 2] / 1_000_000.0
        println("phase  new connection (TCP+TLS+request): %.1f ms to headers, body %.2f ms".format(med(cold.map { it.first }), med(cold.map { it.second })))
        println("phase  reused connection (request only): %.1f ms to headers, body %.2f ms".format(med(warm.map { it.first }), med(warm.map { it.second })))
        val body = """["weath",["weather","weather tomorrow","weather radar","weather channel","weather today","weather dc"]]"""
        repeat(2000) { WebSuggestions.parse(body) }
        val t = System.nanoTime(); repeat(10_000) { WebSuggestions.parse(body) }
        println("phase  parse: %.1f µs".format((System.nanoTime() - t) / 10_000 / 1000.0))
    }

    private fun scenarios() {
        client.connectionPool.evictAll()
        val h = Harness()
        val word = "weather"

        h.scenario("A open search, then type one char at a time (pauses)") {
            h.open(); Thread.sleep(400)
            for (i in 1..word.length) { h.type(word.take(i)); Thread.sleep(800) }
            h.report(word.indices.map { word.take(it + 1) })
        }
        val fast = "pizza near me"
        h.scenario("B fast typing, 110 ms/char") {
            for (i in 1..fast.length) { h.type(fast.take(i)); Thread.sleep(110) }
            Thread.sleep(1500)
            h.reportFast(fast.indices.map { fast.take(it + 1) })
        }
        h.scenario("C backspace 4x, 150 ms each") {
            val steps = (1..4).map { fast.dropLast(it) }
            for (q in steps) { h.type(q); Thread.sleep(150) }
            Thread.sleep(1500)
            h.report(steps)
        }
        h.scenario("D close, reopen after 2 s, type one char at a time") {
            h.close(); Thread.sleep(2000)
            h.open(); Thread.sleep(400)
            val w = "news"
            for (i in 1..w.length) { h.type(w.take(i)); Thread.sleep(800) }
            h.report(w.indices.map { w.take(it + 1) })
        }
        h.scenario("E retype the first word one char at a time") {
            for (i in 1..word.length) { h.type(word.take(i)); Thread.sleep(800) }
            h.report(word.indices.map { word.take(it + 1) })
        }
    }

    private class Harness {
        private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val typed = ConcurrentHashMap<String, Long>()
        private val first = ConcurrentHashMap<String, Long>()
        private val last = ConcurrentHashMap<String, Long>()
        private val ws = WebSuggestions(main) { q, s ->
            if (s.isNotEmpty()) {
                val t = System.nanoTime()
                first.putIfAbsent(q, t)
                last[q] = t
            }
        }
        private var tlsAtStart = 0

        private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

        fun open() = onMain {
            ws.javaClass.methods.firstOrNull { it.name == "prewarm" && it.parameterCount == 0 }?.invoke(ws)
        }
        fun close() = onMain { ws.cancel() }
        fun type(q: String) = onMain { typed[q] = System.nanoTime(); ws.request(q) }

        fun scenario(name: String, body: () -> Unit) {
            typed.clear(); first.clear(); last.clear()
            tlsAtStart = tlsConnections.get()
            println("-- $name")
            body()
        }

        private fun ms(q: String, at: Map<String, Long>) = at[q]?.let { (it - typed.getValue(q)) / 1_000_000 }

        fun report(queries: List<String>) {
            for (q in queries) println("   %-16s first %5s ms   final %5s ms".format("\"$q\"", ms(q, first) ?: "-", ms(q, last) ?: "-"))
            val firsts = queries.mapNotNull { ms(it, first) }.sorted()
            if (firsts.isNotEmpty()) println("   median first %d ms, max %d ms".format(firsts[firsts.size / 2], firsts.last()))
            println("   new TLS connections: ${tlsConnections.get() - tlsAtStart}")
        }

        fun reportFast(queries: List<String>) {
            val start = typed.getValue(queries.first())
            val firstShown = first.values.minOrNull()
            println("   first suggestions on screen %s ms after the first keystroke".format(firstShown?.let { (it - start) / 1_000_000 } ?: "-"))
            println("   queries that showed suggestions while typing: ${queries.dropLast(1).count { first.containsKey(it) }}/${queries.size - 1}")
            println("   final \"%s\": first %s ms, final %s ms after its keystroke".format(queries.last(), ms(queries.last(), first) ?: "-", ms(queries.last(), last) ?: "-"))
            println("   new TLS connections: ${tlsConnections.get() - tlsAtStart}")
        }
    }

    companion object {
        private val rttMs = AtomicInteger(0)
        private val tlsConnections = AtomicInteger(0)
        private val latencyProxy by lazy { LatencyProxy(rttMs) }
        private val client: OkHttpClient by lazy {
            OkHttpClient().apply {
                setProtocols(listOf(Protocol.HTTP_1_1))
                setConnectionPool(ConnectionPool(5, 5 * 60 * 1000L))
                setProxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), latencyProxy.port)))
                setSslSocketFactory(CountingSslSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory()))
            }.also { URL.setURLStreamHandlerFactory(OkUrlFactory(it)) }
        }
    }

    private class CountingSslSocketFactory(private val d: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            d.createSocket(s, host, port, autoClose).also { tlsConnections.incrementAndGet() }
        override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            d.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress?, port: Int): Socket = d.createSocket(host, port)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort)
    }

    /** CONNECT proxy adding [rtt] ms per round trip: one RTT for the TCP handshake, half each way after. */
    private class LatencyProxy(private val rtt: AtomicInteger) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port = server.localPort

        init {
            thread(isDaemon = true) { while (true) { val c = server.accept(); thread(isDaemon = true) { handle(c) } } }
        }

        private fun handle(client: Socket) {
            try {
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it >= 0 }?.toChar() ?: return)
                val (host, port) = head.lineSequence().first().split(' ')[1].split(':')
                val r = rtt.get().toLong()
                if (r > 0) Thread.sleep(r)
                val up = Socket(host, port.toInt()).apply { tcpNoDelay = true }
                client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                pipe(input, up.getOutputStream(), r / 2, client, up)
                pipe(up.getInputStream(), client.getOutputStream(), r / 2, client, up)
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }

        private fun pipe(src: InputStream, dst: OutputStream, delayMs: Long, vararg sockets: Socket) {
            val q = LinkedBlockingQueue<Pair<Long, ByteArray?>>()
            thread(isDaemon = true) {
                val buf = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        q.put(System.nanoTime() + delayMs * 1_000_000 to buf.copyOf(n))
                    }
                } catch (_: Exception) {
                }
                q.put(System.nanoTime() + delayMs * 1_000_000 to null)
            }
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val (due, data) = q.take()
                        val wait = due - System.nanoTime()
                        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        if (data == null) break
                        dst.write(data)
                        dst.flush()
                    }
                } catch (_: Exception) {
                }
                sockets.forEach { runCatching { it.close() } }
            }
        }
    }
}
