package com.meowgi.launcher710.ai

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AiAnswerSessionTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val states: MutableList<AiState> = Collections.synchronizedList(mutableListOf())
    private val session = AiAnswerSession(main) { states += it }
    private val config = AiConfigResult.Ready(AiConfig("https://x/v1", "sk-secret", "m", ""))

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; states=$states")
            Thread.sleep(5)
        }
        onMain { }
    }

    private fun last() = states.lastOrNull()

    @Test
    fun streamsTokensIntoOneAnswer() {
        session.streamer = { _, query, _, emit ->
            assertEquals("what is sse", query)
            emit(AiEvent.Token("Server-"))
            emit(AiEvent.Token("Sent "))
            emit(AiEvent.Token("Events"))
            emit(AiEvent.Done)
        }
        onMain { session.start("what is sse", config) }
        waitFor("done") { (last() as? AiState.Answer)?.done == true }
        assertEquals(AiState.Loading("what is sse"), states.first())
        assertEquals(AiState.Answer("what is sse", "Server-Sent Events", done = true), last())
        assertTrue(session.isActive)
    }

    @Test
    fun missingConfigFailsWithoutARequest() {
        var called = false
        session.streamer = { _, _, _, _ -> called = true }
        onMain { session.start("q", AiConfigResult.Unavailable("Pick a model for AI answers.")) }
        assertEquals(listOf<AiState>(AiState.Failed("q", "Pick a model for AI answers.")), states.toList())
        assertFalse(called)
    }

    @Test
    fun streamFailuresAreShown() {
        session.streamer = { _, _, _, emit ->
            emit(AiEvent.Token("half"))
            emit(AiEvent.Failure("The AI server rejected the API key (401)", AiFailure.AUTH))
        }
        onMain { session.start("q", config) }
        waitFor("failure") { last() is AiState.Failed }
        assertEquals(AiState.Failed("q", "The AI server rejected the API key (401)"), last())
    }

    @Test
    fun aStreamerThatJustReturnsEndsTheAnswer() {
        session.streamer = { _, _, _, emit -> emit(AiEvent.Token("ok")) }
        onMain { session.start("q", config) }
        waitFor("done") { (last() as? AiState.Answer)?.done == true }
        assertEquals(AiState.Answer("q", "ok", done = true), last())
    }

    @Test
    fun cancelAbortsTheRequestAndDropsLateTokens() {
        val firstToken = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<AiCall>()
        session.streamer = { _, _, c, emit ->
            call.set(c)
            emit(AiEvent.Token("early"))
            firstToken.countDown()
            release.await(5, TimeUnit.SECONDS)
            emit(AiEvent.Token(" late"))
            emit(AiEvent.Done)
        }
        onMain { session.start("q", config) }
        assertTrue(firstToken.await(5, TimeUnit.SECONDS))
        waitFor("first token") { last() is AiState.Answer }
        onMain { session.cancel() }
        assertEquals(AiState.Idle, last())
        assertFalse(session.isActive)
        waitFor("HTTP call cancelled") { call.get()?.isCancelled == true }

        release.countDown()
        Thread.sleep(100)
        onMain { }
        assertEquals(AiState.Idle, last())
        assertFalse(states.any { it is AiState.Answer && it.text.contains("late") })
    }

    @Test
    fun aNewTriggerReplacesTheRunningAnswer() {
        val release = CountDownLatch(1)
        session.streamer = { _, query, _, emit ->
            if (query == "first") {
                release.await(5, TimeUnit.SECONDS)
                emit(AiEvent.Token("stale"))
                emit(AiEvent.Done)
            } else {
                emit(AiEvent.Token("fresh"))
                emit(AiEvent.Done)
            }
        }
        onMain { session.start("first", config) }
        onMain { session.start("second", config) }
        waitFor("second answer") { (last() as? AiState.Answer)?.done == true }
        release.countDown()
        Thread.sleep(100)
        onMain { }
        assertEquals(AiState.Answer("second", "fresh", done = true), last())
        assertFalse(states.any { it is AiState.Answer && it.query == "first" })
    }

    @Test
    fun cancelWhenIdleDoesNothing() {
        onMain { session.cancel() }
        assertTrue(states.isEmpty())
    }
}
