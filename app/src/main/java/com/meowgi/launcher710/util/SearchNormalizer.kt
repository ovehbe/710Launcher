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
 *
 * Runs on every keystroke, so nothing here may compile a regex or normalize a label per call;
 * labels are pre-processed once with [normalize] and [words] when the app list is loaded.
 */
object SearchNormalizer {

    const val NO_MATCH = 0
    const val NAME_MATCH = 1
    const val PACKAGE_MATCH = 2

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WORD_SEPARATORS = Regex("[\\s\\p{Punct}]+")

    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        // NFD is the identity on ASCII, which is nearly every query.
        val decomposed = if (input.all { it.code < 0x80 }) input
        else COMBINING_MARKS.replace(Normalizer.normalize(input, Normalizer.Form.NFD), "")

        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
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

    /** Normalized words of [label], split on whitespace and punctuation; empty words dropped. */
    fun words(label: String?): List<String> {
        if (label.isNullOrBlank()) return emptyList()
        return label.trim().split(WORD_SEPARATORS).map { normalize(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Builds initials from a label: first normalized letter of each word.
     * "Trendyol Go" → "tg", "Türkiye Finans" → "tf", "WhatsApp" → "w"
     */
    fun initials(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val words = input.trim().split(WORD_SEPARATORS).filter { it.isNotEmpty() }
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
    fun matchesPrefixPerWord(normalizedQuery: String, originalLabel: String): Boolean =
        matchesPrefixPerWord(normalizedQuery, words(originalLabel))

    /** As above, against words pre-split with [words]. Allocation-free. */
    fun matchesPrefixPerWord(normalizedQuery: String, words: List<String>): Boolean {
        if (normalizedQuery.isEmpty()) return false
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

    /**
     * The single app-matching rule shared by repository search and page-scoped filtering:
     * label substring, prefix-per-word, or dial-digit substring is a [NAME_MATCH]; otherwise a
     * package-name substring is a [PACKAGE_MATCH] (ranked after name matches by callers).
     */
    fun match(
        normalizedQuery: String,
        dialDigits: String?,
        normalizedLabel: String,
        labelWords: List<String>,
        normalizedPackage: String
    ): Int {
        if (normalizedQuery.isNotEmpty()) {
            if (normalizedLabel.contains(normalizedQuery)) return NAME_MATCH
            if (matchesPrefixPerWord(normalizedQuery, labelWords)) return NAME_MATCH
        }
        if (dialDigits != null && normalizedLabel.contains(dialDigits)) return NAME_MATCH
        if (normalizedQuery.isNotEmpty() && normalizedPackage.contains(normalizedQuery)) return PACKAGE_MATCH
        return NO_MATCH
    }
}
