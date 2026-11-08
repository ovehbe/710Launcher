package com.meowgi.launcher710.ui.search

import android.content.Context
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.util.FakeCurrencyRatesCache
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
 * Test calculator/currency chaining: adopt result, EDIT for continued arithmetic, adopt again.
 * Verifies Enter key works without manual navigation for BOTH adoptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterKeyChainingTest {
    
    private lateinit var context: Context
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText
    private var fakeRatesCache: FakeCurrencyRatesCache? = null
    private val looper = shadowOf(android.os.Looper.getMainLooper())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        LauncherPrefs(context).apply {
            searchCalculatorEnabled = true
            searchCurrencyEnabled = true
            setSearchCurrencyList(listOf("TRY", "EUR", "USD", "GBP", "JPY", "IDR"))
        }
    }

    private fun launch() {
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
    }

    private fun launchWithFakeRates() {
        fakeRatesCache = FakeCurrencyRatesCache()
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.testRatesCache = fakeRatesCache
        overlay.testSynchronousUpdate = true
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
    }

    private fun settle() {
        Thread.sleep(100)
        val mainLooper = shadowOf(android.os.Looper.getMainLooper())
        mainLooper.runToEndOfTasks()
        Thread.sleep(50)
        mainLooper.idle()
    }

    private fun pressEnter() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        settle()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        settle()
    }

    @Test
    fun `calculator chaining - adopt then edit then adopt again with NO navigation`() {
        launch()
        
        // Step 1: Type "2+2" and press Enter (NO manual navigation)
        field.setText("2+2")
        settle()
        
        pressEnter()
        
        // Should adopt "4"
        assertEquals("4", field.text.toString())
        assertEquals("Overlay should stay open", android.view.View.VISIBLE, overlay.visibility)
        
        // Verify no external action launched
        assertNull("First Enter should adopt, not search", shadowOf(activity).nextStartedActivity)
        
        // Step 2: User types "+2" to continue calculation
        field.setText("4+2")
        field.setSelection(field.text.length)
        settle()
        
        // Step 3: Press Enter again (NO manual navigation)
        pressEnter()
        
        // Should adopt "6", NOT launch search
        assertEquals("6", field.text.toString())
        assertEquals("Overlay should stay open", android.view.View.VISIBLE, overlay.visibility)
        
        // Verify no external action launched
        assertNull("Second Enter should adopt continued calculation, not search", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `currency chaining - adopt then edit then adopt again with NO navigation`() {
        launchWithFakeRates()
        
        // Step 1: Type "100 usd to eur" and press Enter (NO manual navigation)
        field.setText("100 usd to eur")
        settle()
        
        pressEnter()
        
        // Should adopt currency result (e.g., "85 eur")
        val firstAdopted = field.text.toString()
        assertTrue("Should contain 'eur'", firstAdopted.contains("eur", ignoreCase = true))
        assertEquals("Overlay should stay open", android.view.View.VISIBLE, overlay.visibility)
        
        // Verify no external action launched
        assertNull("First Enter should adopt, not search", shadowOf(activity).nextStartedActivity)
        
        // Step 2: User appends "/10" to continue calculation with retained currency
        val editedQuery = "$firstAdopted/10"
        field.setText(editedQuery)
        field.setSelection(field.text.length)
        settle()
        
        // Step 3: Press Enter again (NO manual navigation)
        pressEnter()
        
        // Should adopt divided result (still in EUR), NOT launch search
        val secondAdopted = field.text.toString()
        assertTrue("Should still contain 'eur'", secondAdopted.contains("eur", ignoreCase = true))
        assertNotEquals("Should be different amount", firstAdopted, secondAdopted)
        assertEquals("Overlay should stay open", android.view.View.VISIBLE, overlay.visibility)
        
        // Verify no external action launched
        assertNull("Second Enter should adopt continued calculation, not search", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `second Enter after chaining launches search`() {
        launch()
        
        // Chain: "2+2" → Enter → "4" → edit to "4+2" → Enter → "6"
        field.setText("2+2")
        settle()
        pressEnter()
        assertEquals("4", field.text.toString())
        
        field.setText("4+2")
        field.setSelection(field.text.length)
        settle()
        pressEnter()
        assertEquals("6", field.text.toString())
        
        // Third Enter with NO edit should search
        pressEnter()
        assertNotNull("Third Enter with no edit should search", shadowOf(activity).nextStartedActivity)
    }
}
