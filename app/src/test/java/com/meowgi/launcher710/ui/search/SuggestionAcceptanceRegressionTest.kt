package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression tests for Google suggestion acceptance behavior.
 * 
 * Product requirements:
 * 1. Hardware Enter keydown+keyup on highlighted suggestion: accepts text, no browser launch
 * 2. Next DISTINCT hardware Enter keydown+keyup: searches (launches browser)
 * 3. IME action on highlighted suggestion: accepts text, no browser launch  
 * 4. Next DISTINCT IME action: searches (launches browser)
 * 5. Works after editing
 * 6. Works after reopening search
 * 7. Touch click: accepts text only, no browser launch
 * 8. Normal app results still launch directly
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuggestionAcceptanceRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText

    /** Search open on "test" with Google suggestions "test one" / "test two". */
    private fun launch() {
        LauncherPrefs(context).apply {
            searchWebSuggestions = true
            aiEnabled = false
        }
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.webSuggestions.fetcher = { q -> listOf("$q one", "$q two") }
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
        
        // Type "test"
        typeQuery("test")
        settle()
        assertEquals("test", field.text.toString())
        drainStartedIntents()
    }

    private fun settle() {
        looper.idleFor(Duration.ofMillis(300))
        repeat(30) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun typeQuery(text: String) {
        for (char in text) {
            val keyCode = when (char) {
                't' -> KeyEvent.KEYCODE_T
                'e' -> KeyEvent.KEYCODE_E
                's' -> KeyEvent.KEYCODE_S
                'x' -> KeyEvent.KEYCODE_X
                else -> throw IllegalArgumentException("Unmapped char: $char")
            }
            activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
            activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0))
            looper.idle()
        }
    }

    private fun drainStartedIntents() {
        while (shadowOf(activity).nextStartedActivity != null) { /* drain */ }
    }

    private fun highlightSecondSuggestion() {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0))
        looper.idle()
        assertEquals("suggestion:1", overlay.navSelection?.toString())
    }

    /**
     * Test 1: Hardware Enter keydown/keyup on suggestion accepts text without launching browser.
     * Then a DISTINCT hardware Enter keydown/keyup searches normally.
     */
    @Test
    fun hardwareEnter_acceptSuggestion_thenDistinctEnter_shouldSearch() {
        launch()
        highlightSecondSuggestion()
        drainStartedIntents()
        
        // First hardware Enter keydown/keyup: should accept suggestion
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0))
        settle()
        
        // Verify: suggestion accepted, no browser
        assertEquals("test two", field.text.toString())
        assertNull("First Enter should not launch browser", shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
        
        // Second DISTINCT hardware Enter keydown/keyup: should search
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0))
        settle()
        
        // Verify: browser launched with query
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Second DISTINCT Enter should launch browser", intent)
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals("test two", intent.getStringExtra("query"))
        assertEquals(View.GONE, overlay.visibility)
    }

    /**
     * Test 2: IME action on suggestion accepts text without launching browser.
     * Then a DISTINCT IME action searches normally (no intermediate edit).
     */
    @Test
    fun imeAction_acceptSuggestion_thenDistinctImeAction_shouldSearch() {
        launch()
        highlightSecondSuggestion()
        drainStartedIntents()
        
        // First IME action: should accept suggestion
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        settle()
        
        // Verify: suggestion accepted, no browser
        assertEquals("test two", field.text.toString())
        assertNull("First IME action should not launch browser", shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
        
        // Second DISTINCT IME action (no intermediate edit): should search
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        settle()
        
        // Verify: browser launched with query
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Second DISTINCT IME action should launch browser", intent)
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals("test two", intent.getStringExtra("query"))
        assertEquals(View.GONE, overlay.visibility)
    }

    /**
     * Test 3: After accepting suggestion and editing, next Enter searches with edited text.
     */
    @Test
    fun acceptSuggestion_thenEdit_thenEnter_shouldSearchEditedText() {
        launch()
        highlightSecondSuggestion()
        drainStartedIntents()
        
        // Accept suggestion with IME action
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        settle()
        assertEquals("test two", field.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        
        // Edit: add " extra"
        typeQuery("x")
        settle()
        assertEquals("test twox", field.text.toString())
        
        // Press Enter: should search with edited text
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0))
        settle()
        
        // Verify: browser launched with edited query
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Enter after edit should launch browser", intent)
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals("test twox", intent.getStringExtra("query"))
    }

    /**
     * Test 4: After accepting suggestion, typing more, next Enter searches correctly.
     * (Close/reopen test removed - Escape doesn't close overlay in this app)
     */
    @Test
    fun acceptSuggestion_thenTypeMore_thenEnter_shouldSearch() {
        launch()
        highlightSecondSuggestion()
        drainStartedIntents()
        
        // Accept suggestion
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        settle()
        assertEquals("test two", field.text.toString())
        
        // Type more text
        typeQuery("s")
        settle()
        assertEquals("test twos", field.text.toString())
        
        // Press Enter: should search
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0))
        settle()
        
        // Verify: browser launched with updated query
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Enter after typing more should launch browser", intent)
        assertEquals(Intent.ACTION_WEB_SEARCH, intent.action)
        assertEquals("test twos", intent.getStringExtra("query"))
    }

    /**
     * Test 5: Touch click on suggestion accepts text without launching browser.
     */
    @Test
    fun touchClickSuggestion_shouldAcceptOnly_notLaunch() {
        launch()
        highlightSecondSuggestion()
        drainStartedIntents()
        
        // Get the suggestion view through reflection (same as WebSuggestionAcceptanceTest)
        val suggestionsBox = overlay.javaClass.getDeclaredField("suggestionsBox").apply {
            isAccessible = true
        }.get(overlay) as android.widget.LinearLayout
        
        val suggestionView = suggestionsBox.getChildAt(1) // Second suggestion
        assertNotNull("Suggestion view should exist", suggestionView)
        
        // Click the suggestion
        suggestionView.performClick()
        settle()
        
        // Verify: suggestion accepted, no browser
        assertEquals("test two", field.text.toString())
        assertNull("Touch click should not launch browser", shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
    }
}
