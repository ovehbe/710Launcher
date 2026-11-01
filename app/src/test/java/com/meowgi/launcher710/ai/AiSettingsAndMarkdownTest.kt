package com.meowgi.launcher710.ai

import com.meowgi.launcher710.ai.AiMarkdown.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsAndMarkdownTest {

    private fun resolve(
        enabled: Boolean = true,
        baseUrl: String = "https://api.openai.com/v1/",
        apiKey: String? = "sk-1",
        hasStoredKey: Boolean = apiKey != null,
        model: String = " gpt-4o-mini ",
        systemPrompt: String = ""
    ) = AiSettings.resolve(enabled, baseUrl, apiKey, hasStoredKey, model, systemPrompt)

    @Test
    fun readyConfigIsNormalized() {
        assertEquals(
            AiConfigResult.Ready(AiConfig("https://api.openai.com/v1", "sk-1", "gpt-4o-mini", "")),
            resolve()
        )
    }

    @Test
    fun eachMissingPieceHasItsOwnHint() {
        assertEquals(AiConfigResult.Unavailable("AI answers are off."), resolve(enabled = false))
        assertEquals(AiConfigResult.Unavailable("Set a valid base URL for AI answers."), resolve(baseUrl = "openai"))
        assertEquals(AiConfigResult.Unavailable("Pick a model for AI answers."), resolve(model = " "))
        val unreadable = resolve(apiKey = null, hasStoredKey = true)
        assertTrue(unreadable is AiConfigResult.Unavailable && unreadable.message.contains("can't be read"))
    }

    @Test
    fun noKeyIsFineForLocalServers() {
        val r = resolve(baseUrl = "http://10.0.0.2:11434/v1", apiKey = null)
        assertEquals("", (r as AiConfigResult.Ready).config.apiKey)
    }

    // ------------------------------------------------------------------ markdown

    private fun spans(r: AiMarkdown.Rendered, kind: Kind) =
        r.spans.filter { it.kind == kind }.map { r.text.substring(it.start, it.end) }

    @Test
    fun paragraphsAndInlineStyles() {
        val r = AiMarkdown.parse("Use **bold**, *italic* and `code`.\nSame paragraph.\n\nNew paragraph with snake_case_name.")
        assertEquals("Use bold, italic and code.\nSame paragraph.\n\nNew paragraph with snake_case_name.", r.text)
        assertEquals(listOf("bold"), spans(r, Kind.BOLD))
        assertEquals(listOf("italic"), spans(r, Kind.ITALIC))
        assertEquals(listOf("code"), spans(r, Kind.CODE))
    }

    @Test
    fun listsHeadingsQuotesAndLinks() {
        val r = AiMarkdown.parse("# Title\n- one\n  - nested\n1. first\n2) second\n> quoted\nSee [docs](https://x.y).")
        assertEquals("Title\n\n•  one\n•  nested\n1.  first\n2.  second\nquoted\nSee docs.", r.text)
        assertEquals(listOf("Title"), spans(r, Kind.HEADING))
        assertEquals(listOf(0, 1, 0, 0), r.spans.filter { it.kind == Kind.LIST_ITEM }.map { it.level })
        assertEquals(listOf("quoted"), spans(r, Kind.QUOTE))
    }

    @Test
    fun codeBlocksKeepTheirContentVerbatim() {
        val r = AiMarkdown.parse("Run:\n```kotlin\nval x = a * b * c\n**not bold**\n```\nDone.")
        assertEquals("Run:\nval x = a * b * c\n**not bold**\n\nDone.", r.text)
        assertEquals(listOf("val x = a * b * c\n**not bold**"), spans(r, Kind.CODE_BLOCK))
        assertTrue(spans(r, Kind.BOLD).isEmpty())
    }

    @Test
    fun halfStreamedMarkupStaysLiteralUntilClosed() {
        assertEquals("a **bol", AiMarkdown.parse("a **bol").text)
        assertEquals("x `co", AiMarkdown.parse("x `co").text)
        // An unclosed fence is still shown as code while the rest streams in.
        val open = AiMarkdown.parse("```\nline 1")
        assertEquals(listOf("line 1"), spans(open, Kind.CODE_BLOCK))
        assertEquals("2 * 3 = 6", AiMarkdown.parse("2 * 3 = 6").text)
    }
}
