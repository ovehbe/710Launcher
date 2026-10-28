package com.meowgi.launcher710.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

/**
 * Pins the precomputed matcher to the pre-1.5.5 per-keystroke implementation (kept below as
 * the reference) and reports the per-keystroke cost of both over a launcher-sized app list.
 */
class SearchNormalizerTest {

    private object Legacy {
        fun normalize(input: String?): String {
            if (input.isNullOrBlank()) return ""
            val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
            val withoutMarks = nfd.replace("\\p{M}+".toRegex(), "")
            val out = StringBuilder(withoutMarks.length)
            for (ch in withoutMarks) {
                when (ch) {
                    'ı', 'İ', 'I' -> { out.append('i'); continue }
                }
                if (Character.isLetterOrDigit(ch)) out.append(ch.lowercaseChar())
            }
            return out.toString()
        }

        fun matchesPrefixPerWord(normalizedQuery: String, originalLabel: String): Boolean {
            if (normalizedQuery.isEmpty()) return false
            val words = originalLabel.trim().split(Regex("[\\s\\p{Punct}]+"))
                .map { normalize(it) }
                .filter { it.isNotEmpty() }
            if (words.size < 2) return false
            var wordIdx = 0
            var queryIdx = 0
            while (queryIdx < normalizedQuery.length && wordIdx < words.size) {
                val word = words[wordIdx]
                var matchLen = 0
                while (matchLen < word.length && queryIdx + matchLen < normalizedQuery.length &&
                    word[matchLen] == normalizedQuery[queryIdx + matchLen]
                ) matchLen++
                if (matchLen == 0) return false
                queryIdx += matchLen
                wordIdx++
            }
            return queryIdx == normalizedQuery.length
        }

        /** The old SearchOverlay.filterItems loop: label normalized and split per item, per keystroke. */
        fun filter(apps: List<Fixture>, query: String, digits: String?): List<Fixture> {
            val nq = normalize(query)
            if (nq.isEmpty() && digits == null) return apps
            val names = mutableListOf<Fixture>()
            val packages = mutableListOf<Fixture>()
            for (app in apps) {
                val normalized = normalize(app.label)
                val text = nq.isNotEmpty() && normalized.contains(nq)
                val prefix = nq.isNotEmpty() && matchesPrefixPerWord(nq, app.label)
                val digit = digits != null && normalized.contains(digits)
                if (text || prefix || digit) names += app
                else if (nq.isNotEmpty() && app.normalizedPackage.contains(nq)) packages += app
            }
            return names + packages
        }
    }

    private class Fixture(val label: String, pkg: String) {
        val normalizedLabel = SearchNormalizer.normalize(label)
        val words = SearchNormalizer.words(label)
        val normalizedPackage = SearchNormalizer.normalize(pkg)
    }

    private fun current(apps: List<Fixture>, query: String, digits: String?): List<Fixture> {
        val nq = SearchNormalizer.normalize(query)
        if (nq.isEmpty() && digits == null) return apps
        val names = mutableListOf<Fixture>()
        val packages = mutableListOf<Fixture>()
        for (app in apps) {
            when (SearchNormalizer.match(nq, digits, app.normalizedLabel, app.words, app.normalizedPackage)) {
                SearchNormalizer.NAME_MATCH -> names += app
                SearchNormalizer.PACKAGE_MATCH -> packages += app
            }
        }
        return names + packages
    }

    private val baseLabels = listOf(
        "WhatsApp", "Trendyol Go", "Türkiye Finans", "Google Maps", "YouTube Music", "Çiçeksepeti",
        "İş Bankası", "Settings", "Phone", "Messages", "Camera", "Ünlü Fotoğraf", "K-9 Mail",
        "Chrome", "Files by Google", "Spotify: Music & Podcasts", "Señor Café", "Wi-Fi Analyzer",
        "710 Launcher", "Ödeme Noktası", "Clock", "Calculator", "Signal", "Telegram X"
    )

    /** ~300 apps, like a well-used phone. */
    private val apps: List<Fixture> = (0 until 300).map { i ->
        val base = baseLabels[i % baseLabels.size]
        val label = if (i < baseLabels.size) base else "$base ${i / baseLabels.size}"
        Fixture(label, "com.example${i % 17}.${base.lowercase().filter { it.isLetter() }}$i")
    }

    private val typedQueries = listOf("whatsapp", "trendyol go", "tgo", "turkiye", "Türk", "is bank", "com.example3",
        "spotify music", "senor", "wifi", "710", "odeme", "k9", "XYZ nothing")

    /** Every prefix of every query, as it would be seen keystroke by keystroke. */
    private val keystrokes = typedQueries.flatMap { q -> (1..q.length).map { q.substring(0, it) } }

    @Test
    fun normalizeMatchesLegacy() {
        val samples = baseLabels + typedQueries + listOf("", "   ", "I İ ı", "ÀÉÎÕÜ", "日本語 テスト", "a\u0301b", "ﬁle")
        for (s in samples) assertEquals("normalize(\"$s\")", Legacy.normalize(s), SearchNormalizer.normalize(s))
    }

    @Test
    fun matcherMatchesLegacyOnEveryKeystroke() {
        for (q in keystrokes) {
            val digits = q.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() }
            assertEquals("query \"$q\"", Legacy.filter(apps, q, digits).map { it.label }, current(apps, q, digits).map { it.label })
        }
    }

    @Test
    fun perKeystrokeCost() {
        fun measure(block: (String) -> Unit): Double {
            repeat(5) { keystrokes.forEach(block) }
            val runs = 20
            val start = System.nanoTime()
            repeat(runs) { keystrokes.forEach(block) }
            return (System.nanoTime() - start) / 1000.0 / (runs * keystrokes.size)
        }
        val legacyUs = measure { q -> Legacy.filter(apps, q, null) }
        val currentUs = measure { q -> current(apps, q, null) }
        println("SEARCH_BENCH apps=${apps.size} keystrokes=${keystrokes.size} " +
            "legacy=%.1fus current=%.1fus speedup=%.0fx".format(legacyUs, currentUs, legacyUs / currentUs))
    }
}
