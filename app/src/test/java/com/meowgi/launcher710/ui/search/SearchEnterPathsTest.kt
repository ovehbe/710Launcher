package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.method.QwertyKeyListener
import android.text.method.TextKeyListener
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.ai.AiState
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Every way an Enter can reach search: as a key through the activity, as a key delivered
 * straight to the field, and as an IME editor action. On the device the IME sees hardware keys
 * before the activity and can turn Enter into `performEditorAction`, which only reaches the
 * field's editor-action listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchEnterPathsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText

    /** Search open on "hjkl" (no results) with Google suggestions "hjkl one" / "hjkl two". */
    private fun launch() {
        LauncherPrefs(context).apply {
            searchWebSuggestions = true
            aiEnabled = true
        }
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.webSuggestions.fetcher = { q -> listOf("$q one", "$q two") }
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI answers are off.") }
        for (c in listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L)) activityKey(c)
        settle()
        assertEquals("hjkl", field.text.toString())
        drainStarted()
    }

    private fun settle() {
        looper.idleFor(Duration.ofMillis(300))
        repeat(30) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun activityKey(code: Int, meta: Int = 0) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        looper.idle()
    }

    /** A key delivered to the field without passing through the activity. */
    private fun fieldKey(code: Int) {
        field.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, 0))
        field.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, 0))
        looper.idle()
    }

    /** What an IME does when it handles Enter itself. */
    private fun imeAction(actionId: Int) {
        field.onCreateInputConnection(EditorInfo())!!.performEditorAction(actionId)
        looper.idle()
    }

    private fun drainStarted() {
        while (shadowOf(activity).nextStartedActivity != null) { /* drain */ }
    }

    private fun highlightSecondSuggestion() {
        activityKey(KeyEvent.KEYCODE_DPAD_DOWN)
        activityKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("suggestion:1", overlay.navSelection?.toString())
    }

    private fun assertOpenedOnly(query: String) {
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, started?.action)
        assertEquals(query, started?.getStringExtra("query"))
        assertNull("one press opens one thing", shadowOf(activity).nextStartedActivity)
        assertEquals(View.GONE, overlay.visibility)
    }

    private fun assertHighlightedRowOpens(press: () -> Unit) {
        launch()
        highlightSecondSuggestion()
        press()
        // NEW BEHAVIOR: Suggestions are accepted into the field, not launched directly
        settle()
        assertEquals("hjkl two", field.text.toString())
        assertNull("Suggestion acceptance does not launch browser", shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    private fun assertQuerySearchedWithoutHighlight(press: () -> Unit) {
        launch()
        press()
        assertOpenedOnly("hjkl")
    }

    @Test fun activityEnter() = assertHighlightedRowOpens { activityKey(KeyEvent.KEYCODE_ENTER) }
    @Test fun activityNumpadEnter() = assertHighlightedRowOpens { activityKey(KeyEvent.KEYCODE_NUMPAD_ENTER) }
    @Test fun activityDpadCenter() = assertHighlightedRowOpens { activityKey(KeyEvent.KEYCODE_DPAD_CENTER) }
    @Test fun fieldEnter() = assertHighlightedRowOpens { fieldKey(KeyEvent.KEYCODE_ENTER) }
    @Test fun fieldNumpadEnter() = assertHighlightedRowOpens { fieldKey(KeyEvent.KEYCODE_NUMPAD_ENTER) }
    @Test fun fieldDpadCenter() = assertHighlightedRowOpens { fieldKey(KeyEvent.KEYCODE_DPAD_CENTER) }
    @Test fun imeActionSearch() = assertHighlightedRowOpens { imeAction(EditorInfo.IME_ACTION_SEARCH) }
    @Test fun imeActionGo() = assertHighlightedRowOpens { imeAction(EditorInfo.IME_ACTION_GO) }
    @Test fun imeActionDone() = assertHighlightedRowOpens { imeAction(EditorInfo.IME_ACTION_DONE) }
    @Test fun imeActionUnspecified() = assertHighlightedRowOpens { imeAction(EditorInfo.IME_ACTION_UNSPECIFIED) }

    @Test
    fun editorActionOnTheEnterKeyUpAfterTheHighlightOpenedDoesNotAlsoSearchTheQuery() {
        // This test verifies that a double editor action behaves correctly.
        // OLD BEHAVIOR: First action launches web search for "hjkl two", second action ignored (overlay already dismissed).
        // NEW BEHAVIOR: First action accepts "hjkl two" into field (overlay stays open), second action searches for "hjkl two" (not original "hjkl").
        launch()
        highlightSecondSuggestion()
        imeAction(EditorInfo.IME_ACTION_SEARCH)
        imeAction(EditorInfo.IME_ACTION_SEARCH)
        settle()
        // Second action launches web search for the accepted suggestion
        val search = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, search?.action)
        assertEquals("hjkl two", search?.getStringExtra("query"))
        // And overlay is dismissed after web search
        assertEquals(View.GONE, overlay.visibility)
        // Field is cleared on dismiss
        assertEquals("", field.text.toString())
    }

    @Test fun noHighlightActivityEnter() = assertQuerySearchedWithoutHighlight { activityKey(KeyEvent.KEYCODE_ENTER) }
    @Test fun noHighlightActivityNumpadEnter() = assertQuerySearchedWithoutHighlight { activityKey(KeyEvent.KEYCODE_NUMPAD_ENTER) }
    @Test fun noHighlightFieldEnter() = assertQuerySearchedWithoutHighlight { fieldKey(KeyEvent.KEYCODE_ENTER) }
    @Test fun noHighlightImeActionSearch() = assertQuerySearchedWithoutHighlight { imeAction(EditorInfo.IME_ACTION_SEARCH) }

    @Test
    fun noHighlightDpadCenterAndOtherImeActionsDoNothing() {
        launch()
        activityKey(KeyEvent.KEYCODE_DPAD_CENTER)
        fieldKey(KeyEvent.KEYCODE_DPAD_CENTER)
        imeAction(EditorInfo.IME_ACTION_DONE)
        imeAction(EditorInfo.IME_ACTION_GO)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test
    fun altEnterAsksAiEvenWithAHighlight() {
        launch()
        highlightSecondSuggestion()
        activityKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals(AiState.Failed("hjkl", "AI answers are off."), overlay.aiSession.state)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun altThenImeSearchActionAsksAiEvenWithAHighlight() {
        launch()
        highlightSecondSuggestion()
        QwertyKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE).onKeyDown(
            field, field.text, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        )
        imeAction(EditorInfo.IME_ACTION_SEARCH)
        assertEquals(AiState.Failed("hjkl", "AI answers are off."), overlay.aiSession.state)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(overlay.visibility == View.VISIBLE)
    }


    private fun imeNewline() {
        field.onCreateInputConnection(EditorInfo())!!.commitText("\n", 1)
        looper.idle()
    }

    @Test fun imeCommittedNewline() = assertHighlightedRowOpens { imeNewline() }
    @Test fun noHighlightImeCommittedNewline() = assertQuerySearchedWithoutHighlight { imeNewline() }

    @Test
    fun aNewlineNeverReachesTheQuery() {
        launch()
        field.onCreateInputConnection(EditorInfo())!!.commitText("x\ny", 1)
        assertEquals("hjklxy", field.text.toString())
    }
}
