package com.meowgi.launcher710.voice

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geometry regression tests for voice progress indicator placement.
 * Tests verify that the spinner and EditText text area do not overlap,
 * and that show/hide operations don't shift query or result positions.
 *
 * These tests should FAIL on commit 3d742d4 (which has ~4-12dp overlap)
 * and PASS after the geometry fix is applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceProgressGeometryTest {

    private lateinit var activity: LauncherActivity

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(LauncherActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
    }

    @Test
    fun testVoiceProgressDoesNotOverlapWithTextArea() {
        // Get the search components
        val actionBarCenter = activity.findViewById<FrameLayout>(R.id.actionBarCenter)
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Make search overlay visible to trigger voice setup
        searchOverlay.visibility = View.VISIBLE
        
        // Make searchInput visible and alpha=1 (normally done when search opens)
        searchInput.visibility = View.VISIBLE
        searchInput.alpha = 1f
        
        // Get voice progress (should be added to actionBarCenter)
        val voiceProgress = findVoiceProgress(actionBarCenter)
        assertNotNull("Voice progress should be added to search bar", voiceProgress)
        voiceProgress!!.visibility = View.VISIBLE
        
        // Force layout pass with realistic dimensions
        actionBarCenter.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBarCenter.layout(0, 0, 1000, 100)
        
        // Get actual measured bounds in parent coordinates
        val textBounds = getTextAreaBounds(searchInput, actionBarCenter)
        val spinnerBounds = getViewBounds(voiceProgress, actionBarCenter)
        
        // Verify no intersection between text area and spinner
        val hasOverlap = Rect.intersects(textBounds, spinnerBounds)
        
        assertFalse(
            "Text area [${textBounds.left}, ${textBounds.right}] must not overlap with spinner [${spinnerBounds.left}, ${spinnerBounds.right}]. " +
            "Text right edge at ${textBounds.right}, spinner left edge at ${spinnerBounds.left}. " +
            "Overlap: ${if (hasOverlap) "${textBounds.right - spinnerBounds.left}dp" else "none"}",
            hasOverlap
        )
        
        // Specifically verify text stays left of spinner
        assertTrue(
            "Text right edge (${textBounds.right}) must be left of spinner left edge (${spinnerBounds.left})",
            textBounds.right <= spinnerBounds.left
        )
    }

    @Test
    fun testVoiceProgressShowHideDoesNotShiftQueryBounds() {
        val actionBarCenter = activity.findViewById<FrameLayout>(R.id.actionBarCenter)
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Setup search
        searchOverlay.visibility = View.VISIBLE
        val voiceProgress = findVoiceProgress(actionBarCenter)
        assertNotNull(voiceProgress)
        
        // Force layout
        actionBarCenter.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBarCenter.layout(0, 0, 1000, 100)
        
        // Measure with spinner hidden
        voiceProgress!!.visibility = View.GONE
        actionBarCenter.requestLayout()
        actionBarCenter.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBarCenter.layout(0, 0, 1000, 100)
        val boundsHidden = getTextAreaBounds(searchInput, actionBarCenter)
        
        // Measure with spinner visible
        voiceProgress.visibility = View.VISIBLE
        actionBarCenter.requestLayout()
        actionBarCenter.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBarCenter.layout(0, 0, 1000, 100)
        val boundsVisible = getTextAreaBounds(searchInput, actionBarCenter)
        
        // Verify bounds unchanged
        assertEquals(
            "Text area bounds must not change when spinner shows/hides",
            boundsHidden,
            boundsVisible
        )
    }

    @Test
    fun testLongQueryDoesNotOverlapSpinner() {
        val actionBarCenter = activity.findViewById<FrameLayout>(R.id.actionBarCenter)
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Setup search with long query
        searchOverlay.visibility = View.VISIBLE
        searchInput.setText("This is a very long search query that should not overlap with the voice progress spinner even when it extends to the maximum text width")
        
        val voiceProgress = findVoiceProgress(actionBarCenter)
        assertNotNull(voiceProgress)
        voiceProgress!!.visibility = View.VISIBLE
        
        // Force layout
        actionBarCenter.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBarCenter.layout(0, 0, 1000, 100)
        
        // Get bounds
        val textBounds = getTextAreaBounds(searchInput, actionBarCenter)
        val spinnerBounds = getViewBounds(voiceProgress, actionBarCenter)
        
        // Verify no overlap even with long text
        assertFalse(
            "Long query text must not overlap with spinner",
            Rect.intersects(textBounds, spinnerBounds)
        )
    }

    /**
     * Get the actual text area bounds of an EditText in parent coordinates.
     * This accounts for the EditText's position and padding.
     */
    private fun getTextAreaBounds(editText: EditText, parent: ViewGroup): Rect {
        // Get EditText bounds in parent coordinates
        val viewLeft = editText.left
        val viewRight = editText.right
        
        // Account for EditText padding to get actual text area
        val textLeft = viewLeft + editText.paddingLeft
        val textRight = viewRight - editText.paddingRight
        val textTop = editText.top + editText.paddingTop
        val textBottom = editText.bottom - editText.paddingBottom
        
        return Rect(textLeft, textTop, textRight, textBottom)
    }

    /**
     * Get view bounds in parent coordinates.
     */
    private fun getViewBounds(view: View, parent: ViewGroup): Rect {
        return Rect(view.left, view.top, view.right, view.bottom)
    }

    /**
     * Find the voice progress view in the action bar center.
     */
    private fun findVoiceProgress(actionBarCenter: ViewGroup): ProgressBar? {
        for (i in 0 until actionBarCenter.childCount) {
            val child = actionBarCenter.getChildAt(i)
            if (child is ProgressBar && child.isIndeterminate) {
                return child
            }
        }
        return null
    }
}
