package com.meowgi.launcher710.ui.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.ui.settings.SettingsActivity
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.SearchCommandData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchTrackpadNavTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText

    /** Six commands under the "/" trigger: a 4-column grid of two rows (4 + 2). */
    private fun launch(webSuggestions: Boolean = false) {
        LauncherPrefs(context).apply {
            searchWebSuggestions = webSuggestions
            searchCommandTrigger = "/"
            setSearchCommands(('a'..'f').map { SearchCommandData("cmd$it", 1, null, "intent:#Intent;action=test.CMD_${it.uppercaseChar()};end", null) })
        }
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.webSuggestions.fetcher = { q -> listOf("$q one", "$q two") }
    }

    private fun press(code: Int, meta: Int = 0): Boolean {
        val down = activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        looper.idle()
        return down
    }

    private fun type(vararg codes: Int) {
        for (c in codes) press(c)
        settle()
    }

    private fun settle() {
        looper.idleFor(Duration.ofMillis(300))
        repeat(30) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun focused(): View? = activity.window.decorView.findFocus()
    private fun selection() = overlay.navSelection?.toString()

    private fun drainStarted() {
        while (shadowOf(activity).nextStartedActivity != null) { /* drain */ }
    }

    private fun trackball(action: Int, x: Float = 0f, y: Float = 0f): Boolean {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TRACKBALL }
        return activity.dispatchTrackballEvent(ev).also { looper.idle() }
    }

    @Test
    fun dpadWalksTheResultGridAndUpPastTheFirstRowReturnsToTheField() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        assertEquals("/", field.text.toString())
        assertNull(selection())

        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("result:0", selection())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("result:4", selection())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("result:5", selection())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the last result stays put", "result:5", selection())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("result:5", selection())
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("result:1", selection())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("result:0", selection())
        press(KeyEvent.KEYCODE_DPAD_RIGHT); press(KeyEvent.KEYCODE_DPAD_RIGHT); press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("result:3", selection())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("right wraps onto the next grid row", "result:4", selection())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("left wraps back", "result:3", selection())

        press(KeyEvent.KEYCODE_DPAD_UP)
        assertNull("up past the first row returns to the field", selection())
        assertTrue(field.isFocused)
        assertEquals("/", field.text.toString())
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test
    fun dpadCenterOpensTheHighlightedResult() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        drainStarted()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("result:1", selection())

        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("test.CMD_B", shadowOf(activity).nextStartedActivity?.action)
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun dpadCenterWithoutAHighlightIsLeftToTheField() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        drainStarted()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test
    fun enterOpensTheHighlightedRowInsteadOfTheFirstResult() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        drainStarted()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT); press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("result:2", selection())

        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("test.CMD_C", shadowOf(activity).nextStartedActivity?.action)
        assertNull("one Enter press opens one thing", shadowOf(activity).nextStartedActivity)
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test
    fun plainEnterWithoutAHighlightKeepsTheFirstResultBehaviour() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        drainStarted()
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("test.CMD_A", shadowOf(activity).nextStartedActivity?.action)
    }

    @Test
    fun typingWhileARowIsHighlightedGoesToTheFieldWithoutLosingCharacters() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("result:0", selection())

        type(KeyEvent.KEYCODE_C)
        assertEquals("/c", field.text.toString())
        assertNull(selection())

        // Focus pushed off the field (as plain DPAD focus search used to do, onto the home pager
        // behind the overlay): the next keystroke must still land in the field.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val behind = field.focusSearch(View.FOCUS_DOWN)!!
        assertTrue(behind.requestFocus())
        assertFalse(field.isFocused)
        type(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_D)
        assertEquals("/cmd", field.text.toString())
        assertTrue(field.isFocused)
        assertNull(selection())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        type(KeyEvent.KEYCODE_DEL)
        assertEquals("/cm", field.text.toString())
        assertNull(selection())
    }

    @Test
    fun navigationKeepsFocusOnTheField() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        val behind = field.focusSearch(View.FOCUS_DOWN)!!
        assertTrue(behind.requestFocus())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("result:0", selection())
        assertTrue(field.isFocused)
    }

    @Test
    fun dialRowIsTheFirstRowAndDpadCenterDials() {
        launch()
        type(KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_DEL)
        type(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5)
        assertEquals("555", field.text.toString())
        drainStarted()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("dial", selection())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val dial = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial?.action)
        assertEquals("tel:555", dial?.data?.toString())
    }

    @Test
    fun googleSuggestionsAreRowsAndDpadCenterSearchesTheHighlightedOne() {
        launch(webSuggestions = true)
        type(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L)
        assertEquals("hjkl", field.text.toString())
        drainStarted()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("suggestion:0", selection())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("suggestion:1", selection())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val search = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, search?.action)
        assertEquals("hjkl two", search?.getStringExtra("query"))
    }

    @Test
    fun aiAnswerSettingsActionIsNavigable() {
        launch()
        type(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J)
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI answers are off.") }
        assertTrue(overlay.startAiAnswer())
        settle()
        drainStarted()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("ai-settings", selection())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, settings?.component?.className)
    }

    @Test
    fun resultChangesClearTheHighlight() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("result:1", selection())
        overlay.applyQuery("/cmd")
        assertNull(selection())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("result:0", selection())
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI answers are off.") }
        overlay.startAiAnswer()
        assertNull(selection())

        overlay.dismiss()
        assertFalse(overlay.navigate(1, 0))
        assertNull(selection())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theHighlightIsDrawnOverTheHighlightedRow() {
        launch()
        type(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("dial", selection())

        val dialRow = overlay.getChildAt(0)
        assertTrue(dialRow.width > 0 && dialRow.height > 0)
        val accent = LauncherPrefs(context).accentColor or 0xFF000000.toInt()
        fun edgePixel(): Int {
            val bmp = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
            overlay.draw(Canvas(bmp))
            val dp = context.resources.displayMetrics.density
            return bmp.getPixel(dialRow.left + dialRow.width / 2, dialRow.top + (1.5f * dp).toInt()) or 0xFF000000.toInt()
        }
        assertEquals(accent, edgePixel())

        press(KeyEvent.KEYCODE_DPAD_UP)
        assertNull(selection())
        assertTrue("no highlight once back in the field", edgePixel() != accent)
    }

    @Test
    fun trackballMotionMovesTheHighlightAndClickActivates() {
        launch()
        type(KeyEvent.KEYCODE_SLASH)
        drainStarted()

        assertTrue(trackball(MotionEvent.ACTION_MOVE, y = 0.6f))
        assertNull("below one DPAD step", selection())
        trackball(MotionEvent.ACTION_MOVE, y = 0.6f)
        assertEquals("result:0", selection())
        trackball(MotionEvent.ACTION_MOVE, x = 2f)
        assertEquals("result:2", selection())
        trackball(MotionEvent.ACTION_MOVE, y = -1f)
        assertNull(selection())
        trackball(MotionEvent.ACTION_MOVE, y = 1f)
        trackball(MotionEvent.ACTION_MOVE, x = 1f)
        assertEquals("result:1", selection())

        assertTrue(trackball(MotionEvent.ACTION_DOWN))
        assertEquals("test.CMD_B", shadowOf(activity).nextStartedActivity?.action)
        assertEquals(View.GONE, overlay.visibility)
        assertTrue("the release of a consumed press is consumed too", trackball(MotionEvent.ACTION_UP))
        assertFalse(trackball(MotionEvent.ACTION_MOVE, y = 1f))
    }
}
