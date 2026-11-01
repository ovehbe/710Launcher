package com.meowgi.launcher710.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenPagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: LauncherPrefs

    @Before
    fun setUp() {
        context.getSharedPreferences("meowgi_launcher710_settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = LauncherPrefs(context)
    }

    @Test
    fun hiddenPagesAreLeftOutOfTheVisibleOrder() {
        prefs.hideAllPage = true
        prefs.hideFrequentPage = true
        assertFalse(prefs.getFilteredPageOrder().contains("all"))
        assertFalse(prefs.getFilteredPageOrder().contains("frequent"))
        assertEquals(prefs.getPageOrder().filter { it != "all" && it != "frequent" }, prefs.getFilteredPageOrder())
    }

    @Test
    fun aPassedOrderIsFilteredTheSameWay() {
        prefs.hideAllPage = true
        assertEquals(listOf("favorites", "frequent"), prefs.getFilteredPageOrder(listOf("favorites", "all", "frequent")))
    }

    @Test
    fun defaultTabOnAHiddenPageFallsBackToFavorites() {
        prefs.defaultTabPageId = "all"
        prefs.hideAllPage = true
        assertEquals("favorites", prefs.effectiveDefaultTabPageId)
    }

    @Test
    fun defaultTabOnAVisiblePageIsKept() {
        prefs.defaultTabPageId = "all"
        assertEquals("all", prefs.effectiveDefaultTabPageId)
    }
}
