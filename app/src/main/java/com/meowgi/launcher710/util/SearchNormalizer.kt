package com.meowgi.launcher710.util

import java.text.Normalizer

/**
 * Utility for normalizing text for search.
 *
 * - Lowercases text
 * - Applies NFD normalization and strips combining marks (diacritics)
 * - Keeps only letters and digits from any script
 * - Applies a few custom mappings (e.g. Turkish dotted/dotless i) so
 *   "Türkiye" matches "turkiye" and vice versa.
 */
object SearchNormalizer {

    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutMarks = nfd.replace("\\p{M}+".toRegex(), "")

        val out = StringBuilder(withoutMarks.length)
        for (ch in withoutMarks) {
            when (ch) {
                'ı', 'İ', 'I' -> {
                    out.append('i')
                    continue
                }
            }

            if (Character.isLetterOrDigit(ch)) {
                out.append(ch.lowercaseChar())
            }
        }

        return out.toString()
    }

    /**
     * Builds initials from a label: first normalized letter of each word.
     * "Trendyol Go" → "tg", "Türkiye Finans" → "tf", "WhatsApp" → "w"
     */
    fun initials(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val words = input.trim().split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
        val sb = StringBuilder(words.size)
        for (word in words) {
            val first = normalize(word.substring(0, minOf(1, word.length)))
            if (first.isNotEmpty()) sb.append(first[0])
        }
        return sb.toString()
    }

    /**
     * Prefix-per-word match: each character in [normalizedQuery] is matched against
     * the start of successive words in [originalLabel]. Words are split from the
     * original label (preserving boundaries) then normalized individually.
     *
     * E.g. query "tgo" matches "Trendyol Go" → words ["trendyol","go"] → "t" starts
     * "trendyol" and "go" starts "go".
     *
     * [normalizedQuery] should already be normalized. [originalLabel] should be the
     * raw label (before normalization) so word boundaries are preserved.
     */
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
            while (matchLen < word.length && queryIdx + matchLen < normalizedQuery.length
                && word[matchLen] == normalizedQuery[queryIdx + matchLen]
            ) {
                matchLen++
            }
            if (matchLen == 0) return false
            queryIdx += matchLen
            wordIdx++
        }

        return queryIdx == normalizedQuery.length
    }
}

