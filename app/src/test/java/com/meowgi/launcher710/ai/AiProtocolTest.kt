package com.meowgi.launcher710.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiProtocolTest {

    private fun chunk(text: String) = """{"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":${JSONObject.quote(text)}}}]}"""

    /** Feeds [parts] as separate network chunks and maps every event like the client does. */
    private fun stream(vararg parts: String): List<AiEvent> {
        val parser = SseParser()
        val events = parts.flatMap { parser.feed(it) } + parser.finish()
        return events.mapNotNull { AiProtocol.parseEvent(it) }
    }

    private fun text(events: List<AiEvent>) = events.filterIsInstance<AiEvent.Token>().joinToString("") { it.text }

    // ------------------------------------------------------------------ SSE

    @Test
    fun parsesAStandardOpenAiStream() {
        val body = "data: ${chunk("Hel")}\n\ndata: ${chunk("lo")}\n\ndata: [DONE]\n\n"
        val events = stream(body)
        assertEquals("Hello", text(events))
        assertEquals(AiEvent.Done, events.last())
    }

    @Test
    fun linesSplitAnywhereAcrossChunks() {
        val body = "data: ${chunk("Hello, ")}\n\ndata: ${chunk("world")}\n\ndata: [DONE]\n\n"
        for (size in listOf(1, 2, 3, 7, 13, 64)) {
            val parts = body.chunked(size).toTypedArray()
            val events = stream(*parts)
            assertEquals("chunk size $size", "Hello, world", text(events))
            assertEquals("chunk size $size", AiEvent.Done, events.last())
        }
    }

    @Test
    fun crlfAndCrLineEndingsWork_evenWhenCrAndLfArriveInDifferentChunks() {
        val events = stream("data: ${chunk("a")}\r", "\n\r\n", "data: ${chunk("b")}\r\rdata: [DONE]\r\n\r\n")
        assertEquals("ab", text(events))
        assertEquals(AiEvent.Done, events.last())
    }

    @Test
    fun commentsKeepAlivesAndRoleOnlyDeltasAreIgnored() {
        val role = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        val usage = """{"choices":[],"usage":{"total_tokens":5}}"""
        val events = stream(": keep-alive\n\n", "data: $role\n\n", "data: ${chunk("x")}\n\n", "data: $usage\n\n", "data: [DONE]\n\n")
        assertEquals(listOf(AiEvent.Token("x"), AiEvent.Done), events)
    }

    @Test
    fun dataWithoutASpaceAndMultiLineDataAreJoined() {
        val p = SseParser()
        val events = p.feed("data:{\"a\":\ndata: 1}\n\n")
        assertEquals(listOf(SseParser.Event("{\"a\":\n1}")), events)
    }

    @Test
    fun serversThatOmitTheBlankLineBetweenEventsStillStream() {
        val events = stream("data: ${chunk("one ")}\ndata: ${chunk("two")}\ndata: [DONE]\n")
        assertEquals("one two", text(events))
        assertEquals(AiEvent.Done, events.last())
    }

    @Test
    fun finalEventWithoutTrailingNewlineIsFlushedByFinish() {
        val p = SseParser()
        assertTrue(p.feed("data: [DONE]").isEmpty())
        assertEquals(listOf(SseParser.Event("[DONE]")), p.finish())
        assertTrue(p.finish().isEmpty())
    }

    @Test
    fun eventNamesAreReported() {
        val events = SseParser().feed("event: error\ndata: {\"message\":\"overloaded\"}\n\n")
        assertEquals(listOf(SseParser.Event("{\"message\":\"overloaded\"}", "error")), events)
        assertEquals(AiEvent.Failure("overloaded"), AiProtocol.parseEvent(events.single()))
    }

    @Test
    fun errorObjectsInTheStreamBecomeFailures() {
        val events = stream("data: ${chunk("partial")}\n\n", "data: {\"error\":{\"message\":\"context length exceeded\",\"type\":\"invalid_request_error\"}}\n\n")
        assertEquals(AiEvent.Failure("context length exceeded"), events.last())
        assertEquals(AiEvent.Failure("bad"), AiProtocol.parseEvent(SseParser.Event("{\"error\":\"bad\"}")))
    }

    @Test
    fun nonJsonDataIsSkipped() {
        assertNull(AiProtocol.parseEvent(SseParser.Event("hello")))
        assertNull(AiProtocol.parseEvent(SseParser.Event("")))
    }

    // ------------------------------------------------------------------ non-streamed + request

    @Test
    fun parsesANonStreamedCompletion() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"**Hi**"}}]}"""
        assertEquals(AiEvent.Token("**Hi**"), AiProtocol.parseCompletion(body))
        assertTrue(AiProtocol.parseCompletion("{\"error\":{\"message\":\"nope\"}}") is AiEvent.Failure)
        assertTrue(AiProtocol.parseCompletion("<html>") is AiEvent.Failure)
        assertEquals(AiEvent.Token("t"), AiProtocol.parseCompletion("""{"choices":[{"text":"t"}]}"""))
    }

    @Test
    fun chatBodyAsksForAStreamAndOnlyIncludesASystemPromptWhenSet() {
        val plain = JSONObject(AiProtocol.chatBody("m1", "  ", "what is sse"))
        assertEquals("m1", plain.getString("model"))
        assertTrue(plain.getBoolean("stream"))
        val msgs = plain.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        assertEquals("what is sse", msgs.getJSONObject(0).getString("content"))

        val withSystem = JSONObject(AiProtocol.chatBody("m1", "Be brief.", "q")).getJSONArray("messages")
        assertEquals("system", withSystem.getJSONObject(0).getString("role"))
        assertEquals("Be brief.", withSystem.getJSONObject(0).getString("content"))
    }

    @Test
    fun baseUrlsAreValidatedAndNormalized() {
        assertTrue(AiProtocol.isValidBaseUrl("https://api.openai.com/v1"))
        assertTrue(AiProtocol.isValidBaseUrl("http://192.168.1.5:11434/v1/"))
        assertFalse(AiProtocol.isValidBaseUrl("api.openai.com/v1"))
        assertFalse(AiProtocol.isValidBaseUrl("https://"))
        assertFalse(AiProtocol.isValidBaseUrl("ftp://x"))
        assertFalse(AiProtocol.isValidBaseUrl("https://a b"))
        assertEquals("https://x/v1/chat/completions", AiProtocol.chatUrl(" https://x/v1/ "))
        assertEquals("https://x/v1/models", AiProtocol.modelsUrl("https://x/v1"))
    }

    // ------------------------------------------------------------------ models

    @Test
    fun parsesTheOpenAiModelList() {
        val body = """{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"babbage-002"},{"id":"GPT-3.5-turbo"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("babbage-002", "GPT-3.5-turbo", "gpt-4o"), AiProtocol.parseModels(body))
    }

    @Test
    fun parsesOtherCompatibleModelListShapes() {
        assertEquals(listOf("llama3", "mistral"), AiProtocol.parseModels("""{"models":[{"name":"mistral"},{"model":"llama3"}]}"""))
        assertEquals(listOf("a", "b"), AiProtocol.parseModels("""["b","a",""]"""))
        assertEquals(emptyList<String>(), AiProtocol.parseModels("""{"data":[]}"""))
    }

    @Test
    fun badModelListsThrowReadableErrors() {
        for (body in listOf("<html>502</html>", "", "{\"object\":\"list\"}", "{\"error\":{\"message\":\"Invalid key\"}}", "42")) {
            try {
                AiProtocol.parseModels(body)
                fail("expected failure for $body")
            } catch (e: AiException) {
                assertTrue(body, !e.message.isNullOrBlank())
            }
        }
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun httpFailuresAreClassified() {
        val auth = AiProtocol.httpFailure(401, """{"error":{"message":"Incorrect API key provided"}}""")
        assertEquals(AiFailure.AUTH, auth.kind)
        assertTrue(auth.message!!.contains("Incorrect API key provided"))
        assertEquals(AiFailure.AUTH, AiProtocol.httpFailure(403, null).kind)
        assertEquals(AiFailure.CONFIG, AiProtocol.httpFailure(404, "<html>").kind)
        assertTrue(AiProtocol.httpFailure(429, null).message!!.contains("429"))
        assertEquals(AiFailure.SERVER, AiProtocol.httpFailure(500, null).kind)
    }

    @Test
    fun theApiKeyIsRedactedFromMessages() {
        val key = "sk-test-1234567890"
        assertEquals("Incorrect API key provided: •••", AiProtocol.redact("Incorrect API key provided: $key", key))
        assertEquals("msg", AiProtocol.redact("msg", null))
        assertFalse(AiConfig("https://x", key, "m", "").toString().contains(key))
    }
}
