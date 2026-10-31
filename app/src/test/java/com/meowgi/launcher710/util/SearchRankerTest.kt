package com.meowgi.launcher710.util

import com.meowgi.launcher710.util.SearchRanker.HALF_LIFE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private class App(val label: String, val pkg: String, val score: Double = 0.0, val at: Long = 0L) {
        val normalizedLabel = SearchNormalizer.normalize(label)
        val words = SearchNormalizer.words(label)
        val normalizedPackage = SearchNormalizer.normalize(pkg)
    }

    private fun tier(q: String, app: App, digits: String? = null) =
        SearchRanker.tier(SearchNormalizer.normalize(q), digits, app.normalizedLabel, app.words, app.normalizedPackage)

    private fun rank(q: String, apps: List<App>, useUsage: Boolean = true, digits: String? = null): List<String> {
        val ranked = apps.mapNotNull { a ->
            val t = tier(q, a, digits)
            if (t == SearchRanker.NO_MATCH) null
            else SearchRanker.Ranked(a, t, SearchRanker.rankKey(a.score, a.at), a.label.lowercase())
        }.toMutableList()
        return SearchRanker.sort(ranked, useUsage).map { it.label }
    }

    // --- Match tiers ---

    @Test
    fun gradesMatchQuality() {
        assertEquals(SearchRanker.TIER_EXACT, tier("maps", App("Maps", "com.google.maps")))
        assertEquals(SearchRanker.TIER_PREFIX, tier("map", App("Maps", "com.google.maps")))
        assertEquals(SearchRanker.TIER_WORD_PREFIX, tier("go", App("Trendyol Go", "com.trendyol.go")))
        assertEquals("prefix-per-word", SearchRanker.TIER_NAME, tier("tgo", App("Trendyol Go", "com.trendyol.go")))
        assertEquals("substring", SearchRanker.TIER_NAME, tier("ap", App("Maps", "com.google.maps")))
        assertEquals("keypad digits", SearchRanker.TIER_NAME, tier("zw0", App("710 Launcher", "com.meowgi"), digits = "710"))
        assertEquals(SearchRanker.TIER_PACKAGE, tier("whatsapp", App("Chat", "com.whatsapp")))
        assertEquals(SearchRanker.NO_MATCH, tier("xyz", App("Maps", "com.google.maps")))
    }

    @Test
    fun tierAgreesWithMatcherOnWhatMatches() {
        val apps = listOf(App("Maps", "com.google.maps"), App("Trendyol Go", "com.trendyol.go"), App("Chat", "com.whatsapp"))
        for (q in listOf("m", "maps", "go", "tgo", "ap", "whatsapp", "zzz", "com")) {
            for (a in apps) {
                val nq = SearchNormalizer.normalize(q)
                val matched = SearchNormalizer.match(nq, null, a.normalizedLabel, a.words, a.normalizedPackage) != SearchNormalizer.NO_MATCH
                assertEquals("$q vs ${a.label}", matched, tier(q, a) != SearchRanker.NO_MATCH)
            }
        }
    }

    @Test
    fun accentsAndCaseDoNotChangeTheTier() {
        assertEquals(SearchRanker.TIER_EXACT, tier("TURKIYE", App("Türkiye", "tr.gov")))
        assertEquals(SearchRanker.TIER_WORD_PREFIX, tier("fin", App("Türkiye Finans", "tr.finans")))
    }

    // --- Ordering ---

    @Test
    fun matchQualityBeatsUsage() {
        val heavyUse = App("Google Maps", "com.google.maps", score = 500.0, at = now)
        val neverUsed = App("Maps", "com.other.maps")
        assertEquals(listOf("Maps", "Google Maps"), rank("maps", listOf(heavyUse, neverUsed)))
    }

    @Test
    fun frecencyOrdersWithinATier() {
        val apps = listOf(
            App("Camera", "a.camera", score = 2.0, at = now - 30 * day),
            App("Calendar", "a.calendar", score = 5.0, at = now),
            App("Calculator", "a.calc")
        )
        assertEquals(listOf("Calendar", "Camera", "Calculator"), rank("ca", apps))
    }

    @Test
    fun recentUseBeatsOldHeavyUse() {
        val oldHeavy = App("Chrome", "a.chrome", score = 100.0, at = now - 70 * day)
        val recentLight = App("Chess", "a.chess", score = 1.0, at = now)
        // 100 halved ten times is ~0.1, below one fresh launch.
        assertEquals(listOf("Chess", "Chrome"), rank("ch", listOf(oldHeavy, recentLight)))
    }

    @Test
    fun usedAppsRankAboveNeverUsedOnesAndTiesFallBackToLabel() {
        // All three are plain substring matches for "ta", so only usage and label decide.
        val apps = listOf(App("Delta", "a.d"), App("Beta", "a.b"), App("Zeta", "a.z", score = 0.001, at = 0))
        assertEquals(listOf("Zeta", "Beta", "Delta"), rank("ta", apps))
    }

    @Test
    fun disablingUsageRanksByTierThenLabel() {
        val apps = listOf(
            App("Calendar", "a.calendar"),
            App("Camera", "a.camera", score = 50.0, at = now),
            App("Ca", "a.ca"),
            App("My Cam", "a.mycam", score = 99.0, at = now)
        )
        // Exact, then label prefix, then word prefix — usage only reorders inside a tier.
        assertEquals(listOf("Ca", "Camera", "Calendar", "My Cam"), rank("ca", apps, useUsage = true))
        assertEquals(listOf("Ca", "Calendar", "Camera", "My Cam"), rank("ca", apps, useUsage = false))
    }

    @Test
    fun sortIsStableForEqualKeys() {
        val items = mutableListOf(
            SearchRanker.Ranked("first", 1, SearchRanker.NO_USAGE, ""),
            SearchRanker.Ranked("second", 1, SearchRanker.NO_USAGE, ""),
            SearchRanker.Ranked("third", 1, SearchRanker.NO_USAGE, "")
        )
        assertEquals(listOf("first", "second", "third"), SearchRanker.sort(items, useUsage = true))
    }

    // --- Decay ---

    @Test
    fun decayHalvesEveryHalfLife() {
        assertEquals(8.0, SearchRanker.decay(8.0, now, now), 1e-9)
        assertEquals(4.0, SearchRanker.decay(8.0, now, now + HALF_LIFE_MS), 1e-9)
        assertEquals(1.0, SearchRanker.decay(8.0, now, now + 3 * HALF_LIFE_MS), 1e-9)
        assertEquals(0.0, SearchRanker.decay(0.0, now, now + HALF_LIFE_MS), 0.0)
    }

    @Test
    fun clockGoingBackwardsDoesNotInflateScores() {
        assertEquals(8.0, SearchRanker.decay(8.0, now, now - 10 * day), 1e-9)
    }

    @Test
    fun bumpDecaysThenAddsOneLaunch() {
        assertEquals(1.0, SearchRanker.bump(0.0, 0, now), 1e-9)
        assertEquals(5.0, SearchRanker.bump(4.0, now, now), 1e-9)
        assertEquals(3.0, SearchRanker.bump(4.0, now - HALF_LIFE_MS, now), 1e-9)
        var score = 0.0
        var at = 0L
        repeat(10) { i ->
            val t = now + i * 1000L
            score = SearchRanker.bump(score, at, t)
            at = t
        }
        // Nine seconds of decay on a seven-day half-life is negligible.
        assertEquals("ten launches in a row", 10.0, score, 1e-3)
    }

    @Test
    fun rankKeyOrdersExactlyLikeDecayedScores() {
        val samples = listOf(
            1.0 to now, 3.0 to now - 5 * day, 40.0 to now - 60 * day, 0.5 to now - day,
            12.0 to now - 14 * day, 2.0 to now - 8 * day, 7.5 to 1_600_000_000_000L
        )
        val later = now + 3 * day
        for ((s1, t1) in samples) for ((s2, t2) in samples) {
            val byDecay = SearchRanker.decay(s1, t1, later).compareTo(SearchRanker.decay(s2, t2, later))
            val byKey = SearchRanker.rankKey(s1, t1).compareTo(SearchRanker.rankKey(s2, t2))
            assertEquals("($s1@$t1) vs ($s2@$t2)", Integer.signum(byDecay), Integer.signum(byKey))
        }
    }

    @Test
    fun rankKeyForNoUsageIsLowest() {
        assertEquals(SearchRanker.NO_USAGE, SearchRanker.rankKey(0.0, now), 0.0)
        assertTrue(SearchRanker.rankKey(1e-9, 0) > SearchRanker.NO_USAGE)
    }
}
