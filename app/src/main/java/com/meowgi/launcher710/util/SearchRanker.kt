package com.meowgi.launcher710.util

import kotlin.math.ln
import kotlin.math.pow

/**
 * Orders search results: match quality first, then frecency (launch count with exponential time
 * decay), then label.
 *
 * Frecency is compared through [rankKey], `log2(score) + at / HALF_LIFE`. Decaying every score to
 * a shared "now" multiplies each by the same factor, so comparing decayed scores is the same as
 * comparing these keys. A cached key therefore never goes stale and no decay runs per keystroke.
 */
object SearchRanker {

    const val HALF_LIFE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Normalized label equals the query. */
    const val TIER_EXACT = 0
    /** Label starts with the query. */
    const val TIER_PREFIX = 1
    /** A later word of the label starts with the query ("go" → "Trendyol Go"). */
    const val TIER_WORD_PREFIX = 2
    /** Any other name match: substring, prefix-per-word ("tgo"), keypad digits. */
    const val TIER_NAME = 3
    /** Only the package name matches. */
    const val TIER_PACKAGE = 4
    const val NO_MATCH = -1

    /** Key for items never launched; ranks below every launched item. */
    const val NO_USAGE = Double.NEGATIVE_INFINITY

    private val LN2 = ln(2.0)

    fun decay(score: Double, at: Long, now: Long): Double {
        if (score <= 0.0) return 0.0
        val elapsed = (now - at).coerceAtLeast(0L)
        return score * 0.5.pow(elapsed.toDouble() / HALF_LIFE_MS)
    }

    /** Score after one more launch at [now]; the new score is stamped at [now]. */
    fun bump(score: Double, at: Long, now: Long): Double = decay(score, at, now) + 1.0

    fun rankKey(score: Double, at: Long): Double =
        if (score <= 0.0) NO_USAGE else ln(score) / LN2 + at.toDouble() / HALF_LIFE_MS

    /**
     * Match tier for an item, or [NO_MATCH]. Whether something matches at all is decided solely by
     * [SearchNormalizer.match]; this only grades the matches it accepts.
     */
    fun tier(
        normalizedQuery: String,
        dialDigits: String?,
        normalizedLabel: String,
        labelWords: List<String>,
        normalizedPackage: String
    ): Int {
        return when (SearchNormalizer.match(normalizedQuery, dialDigits, normalizedLabel, labelWords, normalizedPackage)) {
            SearchNormalizer.NAME_MATCH -> nameTier(normalizedQuery, normalizedLabel, labelWords)
            SearchNormalizer.PACKAGE_MATCH -> TIER_PACKAGE
            else -> NO_MATCH
        }
    }

    private fun nameTier(q: String, label: String, words: List<String>): Int {
        if (q.isEmpty()) return TIER_NAME
        if (label == q) return TIER_EXACT
        if (label.startsWith(q)) return TIER_PREFIX
        for (i in 1 until words.size) if (words[i].startsWith(q)) return TIER_WORD_PREFIX
        return TIER_NAME
    }

    class Ranked<T>(val item: T, val tier: Int, val usageKey: Double, val sortLabel: String)

    fun <T> comparator(useUsage: Boolean): Comparator<Ranked<T>> {
        val byTier = compareBy<Ranked<T>> { it.tier }
        val withUsage = if (useUsage) byTier.thenByDescending { it.usageKey } else byTier
        return withUsage.thenBy { it.sortLabel }
    }

    /** Sorts [ranked] in place and returns the items. */
    fun <T> sort(ranked: MutableList<Ranked<T>>, useUsage: Boolean): List<T> {
        ranked.sortWith(comparator(useUsage))
        return ranked.map { it.item }
    }
}
