package com.meowgi.launcher710.ui.search

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections

/**
 * Runs the real SearchOverlay (and, for key input, the real LauncherActivity) so the dial-row and
 * web-suggestion decisions are checked on the path the device takes, not just the helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchOverlayDecisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var overlay: SearchOverlay
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        LauncherPrefs(context).searchWebSuggestions = true
        overlay = SearchOverlay(context).apply {
            repository = AppRepository.get(context)
            contactModeProvider = { 1 }
            webSuggestions.fetcher = { q -> fetched += q; listOf("$q one", "$q two") }
        }
        overlay.show()
    }

    /** Text of the dial row, or null when it is hidden. */
    private fun dialRow(o: SearchOverlay = overlay): String? {
        val row = o.getChildAt(0) as LinearLayout
        return if (row.visibility == View.VISIBLE) (row.getChildAt(0) as TextView).text.toString() else null
    }

    private fun suggestions(): List<String> {
        val box = (0 until overlay.childCount).map { overlay.getChildAt(it) }
            .filterIsInstance<LinearLayout>().last()
        if (box.visibility != View.VISIBLE) return emptyList()
        return (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() }
    }

    /** Lets the debounce elapse and the background fetch post its result back. */
    private fun settle() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(300))
        repeat(100) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    /** Fetched queries, without the empty pre-warm request sent when search opens. */
    private fun queries() = fetched.filter { it.isNotEmpty() }

    private fun type(query: String) {
        overlay.applyQuery(query)
        settle()
    }

    @Test
    fun digitsShowTheDialRow() {
        type("445")
        assertEquals("Dial: 445", dialRow())
        type("+90 555")
        assertEquals("Dial: +90555", dialRow())
    }

    @Test
    fun numberKeysTypedAsLettersShowTheDialRow() {
        // A BlackBerry-style keyboard types W E R for 1 2 3 unless Alt is applied.
        type("wer")
        assertEquals("Dial: 123", dialRow())
        type("w23")
        assertEquals("Dial: 123", dialRow())
    }

    @Test
    fun wordsHideTheDialRow() {
        type("sjsj")
        assertNull(dialRow())
        type("hello")
        assertNull(dialRow())
    }

    @Test
    fun keypadDigitOnlyMatchesStillGetSuggestions() {
        // "zwj" → keypad digits "71", which match "710 Settings"; that is not a text match.
        type("zwj")
        assertTrue(queries().contains("zwj"))
        assertEquals(listOf("zwj one", "zwj two"), suggestions())
    }

    @Test
    fun noMatchGetsSuggestions() {
        type("hjkl")
        assertEquals(listOf("hjkl one", "hjkl two"), suggestions())
    }

    @Test
    fun textMatchesSuppressSuggestions() {
        type("sett")
        assertTrue(queries().isEmpty())
        assertEquals(emptyList<String>(), suggestions())
    }

    @Test
    fun dialableQueriesGetTheDialRowInsteadOfSuggestions() {
        type("445")
        type("wer")
        assertTrue(queries().isEmpty())
        assertEquals(emptyList<String>(), suggestions())
    }

    @Test
    fun disabledSettingMeansNoSuggestions() {
        LauncherPrefs(context).searchWebSuggestions = false
        overlay.show()
        type("hjkl")
        assertTrue(queries().isEmpty())
    }

    @Test
    fun openingSearchPrewarmsTheConnectionOnlyWhenEnabled() {
        settle()
        assertEquals(listOf(""), fetched.toList())
        fetched.clear()
        LauncherPrefs(context).searchWebSuggestions = false
        overlay.show()
        settle()
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun rawNumberKeysOnTheHomeScreenShowTheDialRow() {
        // The activity's own overlay has no fake fetcher; keep it off the network.
        LauncherPrefs(context).searchWebSuggestions = false
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        for (code in listOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R)) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            looper.idle()
        }
        val o = activity.findViewById<SearchOverlay>(R.id.searchOverlay)
        assertEquals(View.VISIBLE, o.visibility)
        assertEquals("Dial: 123", dialRow(o))
    }
}
