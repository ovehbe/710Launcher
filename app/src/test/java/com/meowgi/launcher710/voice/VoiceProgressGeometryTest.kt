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
 * Geometry regression tests for unified progress indicator placement (v1.9.8+).
 * Progress indicator replaces magnifier icon in-place in search button container.
 * Tests verify no layout shifts occur and proper positioning is maintained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceProgressGeometryTest {

    private lateinit var activity: LauncherActivity
    private lateinit var progressIndicator: ProgressBar
    private lateinit var magnifierIcon: android.widget.ImageView
    private lateinit var searchButton: FrameLayout

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(LauncherActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
        
        // Get unified progress indicator components
        progressIndicator = activity.findViewById(R.id.progressIndicator)
        magnifierIcon = activity.findViewById(R.id.btnSearch)
        searchButton = activity.findViewById(R.id.btnSearchContainer)
    }

    @Test
    fun testUnifiedProgressIndicatorInSearchButton() {
        // Verify unified progress indicator is properly contained in search button
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Make search overlay visible
        searchOverlay.visibility = View.VISIBLE
        searchInput.visibility = View.VISIBLE
        searchInput.alpha = 1f
        
        // Progress indicator should be in search button container
        assertEquals("Progress indicator parent", searchButton, progressIndicator.parent)
        assertEquals("Magnifier icon parent", searchButton, magnifierIcon.parent)
        
        // Initially: magnifier visible, progress hidden
        assertEquals("Magnifier visible initially", View.VISIBLE, magnifierIcon.visibility)
        assertEquals("Progress hidden initially", View.GONE, progressIndicator.visibility)
        
        // Simulate progress active
        progressIndicator.visibility = View.VISIBLE
        magnifierIcon.visibility = View.GONE
        
        // Force layout
        val actionBar = searchButton.parent as ViewGroup
        actionBar.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBar.layout(0, 0, 1000, 100)
        
        // Search button container should have fixed size (44dp)
        assertEquals("Search button container width", 44, searchButton.width)
        
        // Text input should be in separate container (actionBarCenter) with proper spacing
        val textBounds = getTextAreaBounds(searchInput, actionBar)
        val buttonBounds = Rect(searchButton.left, searchButton.top, searchButton.right, searchButton.bottom)
        
        // Verify no overlap (button is at trailing edge, text is in center container)
        assertFalse(
            "Text area must not overlap with search button container",
            Rect.intersects(textBounds, buttonBounds)
        )
    }

    @Test
    fun testProgressToggleDoesNotShiftLayout() {
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Setup search
        searchOverlay.visibility = View.VISIBLE
        searchInput.visibility = View.VISIBLE
        searchInput.alpha = 1f
        
        val actionBar = searchButton.parent as ViewGroup
        
        // Force layout with magnifier visible (idle state)
        magnifierIcon.visibility = View.VISIBLE
        progressIndicator.visibility = View.GONE
        actionBar.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBar.layout(0, 0, 1000, 100)
        val boundsIdle = getTextAreaBounds(searchInput, actionBar)
        val buttonBoundsIdle = Rect(searchButton.left, searchButton.top, searchButton.right, searchButton.bottom)
        
        // Toggle to progress visible (active state)
        magnifierIcon.visibility = View.GONE
        progressIndicator.visibility = View.VISIBLE
        actionBar.requestLayout()
        actionBar.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBar.layout(0, 0, 1000, 100)
        val boundsActive = getTextAreaBounds(searchInput, actionBar)
        val buttonBoundsActive = Rect(searchButton.left, searchButton.top, searchButton.right, searchButton.bottom)
        
        // Verify text area bounds unchanged
        assertEquals(
            "Text area bounds must not change when progress toggles",
            boundsIdle,
            boundsActive
        )
        
        // Verify search button container bounds unchanged
        assertEquals(
            "Search button bounds must not change when progress toggles",
            buttonBoundsIdle,
            buttonBoundsActive
        )
    }

    @Test
    fun testLongQueryDoesNotCauseOverflow() {
        val searchInput = activity.findViewById<EditText>(R.id.searchInputInBar)
        val searchOverlay = activity.findViewById<View>(R.id.searchOverlay)
        
        // Setup search with long query
        searchOverlay.visibility = View.VISIBLE
        searchInput.visibility = View.VISIBLE
        searchInput.alpha = 1f
        searchInput.setText("This is a very long search query that should not overflow or interfere with the search button even when text reaches maximum width")
        
        // Set progress visible to verify active state
        progressIndicator.visibility = View.VISIBLE
        magnifierIcon.visibility = View.GONE
        
        // Force layout
        val actionBar = searchButton.parent as ViewGroup
        actionBar.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        actionBar.layout(0, 0, 1000, 100)
        
        // Get bounds
        val textBounds = getTextAreaBounds(searchInput, actionBar)
        val buttonBounds = Rect(searchButton.left, searchButton.top, searchButton.right, searchButton.bottom)
        
        // Verify no overlap (text is in separate container with proper padding)
        assertFalse(
            "Long query text must not overlap with search button",
            Rect.intersects(textBounds, buttonBounds)
        )
        
        // Text should stay well left of button due to EditText's 28dp paddingEnd
        assertTrue(
            "Text should be constrained by padding",
            textBounds.right < buttonBounds.left
        )
    }

    /**
     * Get the actual text area bounds of an EditText in parent coordinates.
     * This accounts for the EditText's position and padding.
     */
    private fun getTextAreaBounds(editText: EditText, parent: ViewGroup): Rect {
        // Find EditText position relative to parent
        var left = editText.left
        var right = editText.right
        var top = editText.top
        var bottom = editText.bottom
        
        // Traverse up to parent to get absolute coordinates
        var currentParent = editText.parent as? ViewGroup
        while (currentParent != null && currentParent != parent) {
            left += currentParent.left
            right += currentParent.left
            top += currentParent.top
            bottom += currentParent.top
            currentParent = currentParent.parent as? ViewGroup
        }
        
        // Account for EditText padding to get actual text area
        val textLeft = left + editText.paddingLeft
        val textRight = right - editText.paddingRight
        val textTop = top + editText.paddingTop
        val textBottom = bottom - editText.paddingBottom
        
        return Rect(textLeft, textTop, textRight, textBottom)
    }
}
