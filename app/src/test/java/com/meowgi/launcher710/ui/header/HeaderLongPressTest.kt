package com.meowgi.launcher710.ui.header

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Test clock and date long-press actions dispatch correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeaderLongPressTest {
    
    private lateinit var context: Context
    private lateinit var activity: LauncherActivity
    private val looper = shadowOf(android.os.Looper.getMainLooper())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun launch() {
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
    }
    
    private fun getHeaderView(): com.meowgi.launcher710.ui.header.HeaderView = 
        activity.findViewById(R.id.headerView)
    private fun getHeaderDate(): View = getHeaderView().dateText
    private fun getHeaderClock(): View = getHeaderView().clockText

    @Test
    fun `date long-press with no action configured does nothing`() {
        LauncherPrefs(context).headerDateLongPressAction = 0
        launch()
        
        val handled = getHeaderDate().performLongClick()
        
        assertTrue("Long-press should be handled", handled)
        assertNull("No intent should launch", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `date long-press launches intent when configured with URI`() {
        // Configure with action=2 (launch intent URI)
        val testIntentUri = "intent:#Intent;action=android.intent.action.VIEW;end"
        LauncherPrefs(context).apply {
            headerDateLongPressAction = 2
            headerDateLongPressActionPackage = null
            headerDateLongPressActionIntentUri = testIntentUri
        }
        launch()
        
        val handled = getHeaderDate().performLongClick()
        
        assertTrue("Long-press should be handled", handled)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Intent should launch", intent)
        assertEquals("Should have VIEW action", Intent.ACTION_VIEW, intent.action)
    }

    @Test
    fun `clock long-press with no action configured does nothing`() {
        LauncherPrefs(context).headerClockLongPressAction = 0
        launch()
        
        val handled = getHeaderClock().performLongClick()
        
        assertTrue("Long-press should be handled", handled)
        assertNull("No intent should launch", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `clock long-press launches intent when configured with URI`() {
        // Configure with action=2 (launch intent URI)
        val testIntentUri = "intent:#Intent;action=android.intent.action.MAIN;end"
        LauncherPrefs(context).apply {
            headerClockLongPressAction = 2
            headerClockLongPressActionPackage = null
            headerClockLongPressActionIntentUri = testIntentUri
        }
        launch()
        
        val handled = getHeaderClock().performLongClick()
        
        assertTrue("Long-press should be handled", handled)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Intent should launch", intent)
        assertEquals("Should have MAIN action", Intent.ACTION_MAIN, intent.action)
    }

    @Test
    fun `date and clock long-press actions are independent`() {
        // Configure different actions for each
        val testIntentUri = "intent:#Intent;action=android.intent.action.VIEW;end"
        LauncherPrefs(context).apply {
            headerDateLongPressAction = 2  // Launch intent
            headerDateLongPressActionPackage = null
            headerDateLongPressActionIntentUri = testIntentUri
            
            headerClockLongPressAction = 0  // Nothing
        }
        launch()
        
        // Date should launch intent
        getHeaderDate().performLongClick()
        val dateIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Date long-press should launch intent", dateIntent)
        
        // Clock should do nothing
        shadowOf(activity).clearNextStartedActivities()
        getHeaderClock().performLongClick()
        assertNull("Clock long-press should do nothing", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `long-press persists across activity restart`() {
        // Configure clock long-press
        val testIntentUri = "intent:#Intent;action=android.intent.action.MAIN;end"
        LauncherPrefs(context).apply {
            headerClockLongPressAction = 2  // Launch intent
            headerClockLongPressActionPackage = null
            headerClockLongPressActionIntentUri = testIntentUri
        }
        
        // First launch
        launch()
        getHeaderClock().performLongClick()
        assertNotNull("First launch should work", shadowOf(activity).nextStartedActivity)
        
        // Recreate activity
        activity.recreate()
        looper.idle()
        
        // Should still work (headerClock getter will fetch new view)
        shadowOf(activity).clearNextStartedActivities()
        getHeaderClock().performLongClick()
        assertNotNull("After restart should still work", shadowOf(activity).nextStartedActivity)
    }
}
