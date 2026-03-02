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

        // Normalize and strip diacritics (e.g. ü -> u, ö -> o, ç -> c).
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutMarks = nfd.replace("\\p{M}+".toRegex(), "")

        val out = StringBuilder(withoutMarks.length)
        for (ch in withoutMarks) {
            // Custom mappings first (especially for Turkish i/ı/İ/I).
            when (ch) {
                'ı', 'İ', 'I' -> {
                    out.append('i')
                    continue
                }
            }

            if (Character.isLetterOrDigit(ch)) {
                // Use Unicode-aware lowercase so non-Latin scripts still compare sensibly.
                out.append(ch.lowercaseChar())
            }
            // Drop spaces, punctuation, symbols, etc. from the normalized form.
        }

        return out.toString()
    }
}

