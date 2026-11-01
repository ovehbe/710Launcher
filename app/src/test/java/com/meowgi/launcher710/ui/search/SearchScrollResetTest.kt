package com.meowgi.launcher710.ui.search

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.util.SearchCommandData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchScrollResetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun press(activity: LauncherActivity, code: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        looper.idle()
    }

    @Test
    fun reopeningSearchStartsAtTheTopWithNoHighlight() {
        // 40 commands under "/": a 4-column grid of 10 rows, taller than the results area.
        LauncherPrefs(context).apply {
            searchWebSuggestions = false
            searchCommandTrigger = "/"
            setSearchCommands((1..40).map { SearchCommandData("cmd$it", 1, null, "intent:#Intent;action=test.CMD$it;end", null) })
        }
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        val overlay = activity.findViewById<SearchOverlay>(R.id.searchOverlay)
        val field = activity.findViewById<EditText>(R.id.searchInputInBar)
        val results = (0 until overlay.childCount).map { overlay.getChildAt(it) }.filterIsInstance<RecyclerView>().single()
        val aiPanel = (0 until overlay.childCount).map { overlay.getChildAt(it) }.filterIsInstance<ScrollView>().single()
        fun firstVisible() = (results.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()

        press(activity, KeyEvent.KEYCODE_SLASH)
        assertEquals("/", field.text.toString())
        assertEquals(40, results.adapter!!.itemCount)
        results.scrollToPosition(39)
        looper.idle()
        assertTrue("the list scrolled", firstVisible() > 0)
        // A highlight on a lower row, too.
        repeat(3) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals("result:8", overlay.navSelection?.toString())
        aiPanel.scrollTo(0, 50)

        press(activity, KeyEvent.KEYCODE_BACK)
        assertEquals(View.GONE, overlay.visibility)

        // Reopen with the same query, so the very same results come back.
        press(activity, KeyEvent.KEYCODE_SLASH)
        assertEquals(View.VISIBLE, overlay.visibility)
        assertEquals("/", field.text.toString())
        assertEquals(40, results.adapter!!.itemCount)
        assertEquals("results start at the top", 0, firstVisible())
        assertEquals(0, results.computeVerticalScrollOffset())
        assertEquals(0, aiPanel.scrollY)
        assertNull("no highlight carried over", overlay.navSelection)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("navigation starts from the first row again", "result:0", overlay.navSelection?.toString())
    }
}
