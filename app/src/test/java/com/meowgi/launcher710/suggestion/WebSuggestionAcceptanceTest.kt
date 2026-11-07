package com.meowgi.launcher710.suggestion

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.ui.search.SearchOverlay
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * Tests that clicking Google suggestions or activating them with Enter accepts the
 * suggestion text into the search field instead of launching the browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebSuggestionAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: LauncherPrefs
    private lateinit var overlay: SearchOverlay
    private var currentFieldText = ""
    private var fieldUpdateCount = 0

    @Before
    fun setup() {
        prefs = LauncherPrefs(context)
        prefs.searchWebSuggestions = true
        
        overlay = SearchOverlay(context).apply {
            repository = AppRepository.get(context)
            onUpdateSearchText = { text ->
                currentFieldText = text
                fieldUpdateCount++
                // Let overlay process the update
                applyQuery(text)
            }
            // Override fetcher to provide instant suggestions without network
            webSuggestions.fetcher = { q -> listOf("$q one", "$q two", "$q three") }
        }
        
        currentFieldText = ""
        fieldUpdateCount = 0
    }

    @After
    fun tearDown() {
        overlay.dismiss()
    }

    /** Lets the debounce elapse and the background fetch post its result back. */
    private fun settle() {
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(300))
        repeat(100) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun suggestions(): List<String> {
        val box = (0 until overlay.childCount).map { overlay.getChildAt(it) }
            .filterIsInstance<android.widget.LinearLayout>().last()
        if (box.visibility != android.view.View.VISIBLE) return emptyList()
        return (0 until box.childCount).map { (box.getChildAt(it) as android.widget.TextView).text.toString() }
    }

    @Test
    fun clickWebSuggestion_shouldAcceptTextIntoField_notLaunchBrowser() {
        // Setup: Show search overlay and type a query
        overlay.show()
        settle()
        
        overlay.applyQuery("weather")
        settle()
        
        // Verify suggestions appeared
        val suggestionsList = suggestions()
        assertTrue("Should have suggestions", suggestionsList.isNotEmpty())
        assertEquals("Should have weather one", "weather one", suggestionsList[0])
        
        val initialUpdateCount = fieldUpdateCount
        
        // Get the suggestion view and click it
        val suggestionsBox = overlay.javaClass.getDeclaredField("suggestionsBox").apply {
            isAccessible = true
        }.get(overlay) as android.widget.LinearLayout
        
        val suggestionView = suggestionsBox.getChildAt(0)
        assertNotNull("Suggestion view should exist", suggestionView)
        
        // Click the suggestion
        suggestionView.performClick()
        settle()
        
        // Verify: Field should be updated with suggestion text
        assertEquals("Field should contain suggestion text", "weather one", currentFieldText)
        assertTrue("Field should have been updated", fieldUpdateCount > initialUpdateCount)
        
        // Verify: No browser intent was launched
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNull("No browser intent should be launched", nextIntent)
        
        // Verify: Search overlay is still visible (not dismissed)
        assertEquals("Search overlay should remain visible", android.view.View.VISIBLE, overlay.visibility)
    }

    @Test
    fun suggestionPerformClick_shouldAcceptText_notLaunchBrowser() {
        // This test verifies that performClick() accepts the suggestion into the field.
        // activateNavSelection() for a highlighted suggestion calls performClick() on that view,
        // so this test covers both the direct click and the Enter-on-highlighted-suggestion paths.
        
        overlay.show()
        settle()
        
        overlay.applyQuery("android")
        settle()
        
        // Verify suggestions appeared
        val suggestionsList = suggestions()
        assertTrue("Should have suggestions", suggestionsList.isNotEmpty())
        
        val initialUpdateCount = fieldUpdateCount
        
        // Get the suggestion view and call performClick (same as Enter would do)
        val suggestionsBox = overlay.javaClass.getDeclaredField("suggestionsBox").apply {
            isAccessible = true
        }.get(overlay) as android.widget.LinearLayout
        
        val suggestionView = suggestionsBox.getChildAt(0)
        assertNotNull("Suggestion view should exist", suggestionView)
        
        // performClick is what activateNavSelection() calls for NavTarget.Suggestion
        suggestionView.performClick()
        settle()
        
        // Verify: Field should contain suggestion text
        assertEquals("Field should contain suggestion text", "android one", currentFieldText)
        assertTrue("Field should have been updated", fieldUpdateCount > initialUpdateCount)
        
        // Verify: No browser intent launched
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNull("No browser intent should be launched", nextIntent)
        
        // Verify: Search overlay still visible
        assertEquals("Search overlay should remain visible", android.view.View.VISIBLE, overlay.visibility)
    }

}
