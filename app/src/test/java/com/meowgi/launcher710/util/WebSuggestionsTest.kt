package com.meowgi.launcher710.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSuggestionsTest {

    @Test
    fun parsesFirefoxClientResponse() {
        val body = """["weather",["weather","weather tomorrow","weather radar","weatherford"]]"""
        assertEquals(
            listOf("weather", "weather tomorrow", "weather radar", "weatherford"),
            WebSuggestions.parse(body)
        )
    }

    @Test
    fun ignoresTrailingMetadataElements() {
        val body = """["pix",["pixel 9","pixar"],["",""],{"google:suggesttype":["QUERY","QUERY"]}]"""
        assertEquals(listOf("pixel 9", "pixar"), WebSuggestions.parse(body))
    }

    @Test
    fun keepsUnicode() {
        val body = """["istanbul",["istanbul hava durumu","İstanbul Üniversitesi","東京"]]"""
        assertEquals(listOf("istanbul hava durumu", "İstanbul Üniversitesi", "東京"), WebSuggestions.parse(body))
    }

    @Test
    fun dropsBlankNonStringAndDuplicateEntries() {
        val body = """["a",["  alpha  ","",null,42,"Alpha","beta",{"x":1},"BETA","gamma"]]"""
        assertEquals(listOf("alpha", "beta", "gamma"), WebSuggestions.parse(body))
    }

    @Test
    fun capsTheNumberOfSuggestions() {
        val items = (1..20).joinToString(",") { "\"s$it\"" }
        val parsed = WebSuggestions.parse("""["s",[$items]]""")
        assertEquals(WebSuggestions.MAX_SUGGESTIONS, parsed.size)
        assertEquals("s1", parsed.first())
        assertEquals(listOf("s1", "s2"), WebSuggestions.parse("""["s",[$items]]""", limit = 2))
    }

    @Test
    fun malformedResponsesYieldNothing() {
        for (body in listOf(
            "",
            "   ",
            "<html>503 Service Unavailable</html>",
            """["weather",["weather","weath""",
            """{"query":"weather"}""",
            """["weather"]""",
            """["weather","not a list"]""",
            """["weather",{}]""",
            "[]",
            "null"
        )) {
            assertTrue("\"$body\" should give no suggestions", WebSuggestions.parse(body).isEmpty())
        }
    }

    @Test
    fun emptySuggestionListYieldsNothing() {
        assertTrue(WebSuggestions.parse("""["zzqxv",[]]""").isEmpty())
    }

    @Test
    fun urlEncodesQueryAndLanguage() {
        val url = WebSuggestions.buildUrl("c++ & café #1", language = "tr")
        assertTrue(url.startsWith("https://suggestqueries.google.com/complete/search?client=firefox"))
        assertTrue(url.contains("&ie=UTF-8&oe=UTF-8"))
        assertTrue(url.contains("&hl=tr"))
        assertTrue(url, url.endsWith("&q=c%2B%2B+%26+caf%C3%A9+%231"))
        assertTrue(WebSuggestions.buildUrl("x", language = "").contains("&hl=en"))
    }
}
