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
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * BOUNDARY TEST: Verify actual SearchOverlay adoption behavior with real key dispatch.
 * 
 * Tests the PREMISE that parser/converter unit tests alone prove UI adoption correctness.
 * Exercises REAL overlay query/results + REAL hardware key dispatch.
 * 
 * Current HEAD: 48f8514
 * 
 * HYPOTHESIS TO TEST:
 * - R1 fix (source-first results) may have changed initial unadopted behavior
 * - Initial "100 yen / 10" should convert to configured target (TRY), not stay in JPY
 * - After adopting "X try", appending "/2" should stay in TRY
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CurrencyAdoptionBoundaryTest {
    
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val looper = shadowOf(android.os.Looper.getMainLooper())
    
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText
    private lateinit var fakeRatesCache: FakeCurrencyRatesCache
    
    @Before
    fun setup() {
        // Configure prefs: currency enabled, target display currencies
        LauncherPrefs(context).apply {
            searchWebSuggestions = false
            aiEnabled = false
            searchCalculatorEnabled = true
            searchCurrencyEnabled = true
            // Configure TRY as display target
            setSearchCurrencyList(listOf("TRY", "EUR", "USD"))
        }
        
        // Create fake rates cache with deterministic rates
        fakeRatesCache = FakeCurrencyRatesCache()
        
        // Build activity with full lifecycle: create().start().resume()
        // This ensures lifecycleScope is active
        activity = Robolectric.buildActivity(LauncherActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
        
        // Idle all pending work
        looper.idle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        
        field = activity.findViewById(R.id.searchInputInBar)
        overlay = activity.findViewById(R.id.searchOverlay)
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
        
        // Inject fake rates cache and enable synchronous updates for testing
        overlay.testRatesCache = fakeRatesCache
        overlay.testSynchronousUpdate = true
        
        // Clear any startup state
        field.setText("")
        looper.idle()
        
        println("=== SETUP COMPLETE ===")
        println("Activity lifecycle state: ${activity.lifecycle.currentState}")
        println("Test rates cache injected: ${overlay.testRatesCache != null}")
    }
    
    private fun typeIntoField(text: String) {
        field.setText(text)
        field.setSelection(text.length)
        // Don't idle yet - let settle() handle it
    }
    
    private fun hardwareEnterDown() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }
    
    private fun hardwareEnterUp() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
    
    private fun settle() {
        // Give coroutine time to post runnables
        Thread.sleep(100)
        
        // Run all scheduled tasks
        val mainLooper = shadowOf(android.os.Looper.getMainLooper())
        mainLooper.runToEndOfTasks()
        
        // Also idle the activity looper
        looper.runToEndOfTasks()
        
        // One more drain cycle
        mainLooper.idle()
        looper.idle()
    }
    
    @Test
    fun `BOUNDARY PREMISE - initial unadopted 100 yen div 10 shows configured target TRY first or JPY first`() {
        // PREMISE: Initial unadopted query converts to configured display targets
        // R1 fix made source appear first - does this break initial unadopted behavior?
        
        println("=== TEST START ===")
        println("Before typing, currency enabled: ${LauncherPrefs(context).searchCurrencyEnabled}")
        
        // Type fresh unadopted query
        typeIntoField("100 yen / 10")
        println("Typed '100 yen / 10', settling...")
        settle()
        println("Settle complete")
        
        // Check what currency rows are visible
        val currencyRowsContainer = overlay.currencyRowsContainer
        println("Currency rows visible: ${currencyRowsContainer.childCount}")
        println("Currency rows visibility: ${currencyRowsContainer.visibility}")
        
        if (currencyRowsContainer.childCount > 0) {
            // Extract text from each row
            for (i in 0 until currencyRowsContainer.childCount) {
                val row = currencyRowsContainer.getChildAt(i) as? android.widget.LinearLayout
                val textView = row?.getChildAt(0) as? android.widget.TextView
                val text = textView?.text?.toString() ?: ""
                println("Row $i: $text")
            }
            
            // Get first row to check currency
            val firstRow = currencyRowsContainer.getChildAt(0) as? android.widget.LinearLayout
            val firstTextView = firstRow?.getChildAt(0) as? android.widget.TextView
            val firstText = firstTextView?.text?.toString() ?: ""
            
            println("=== OBSERVATION ===")
            println("Query: '100 yen / 10'")
            println("First row text: '$firstText'")
            
            val hasJPY = firstText.contains("JPY", ignoreCase = true) || firstText.contains("¥")
            val hasTRY = firstText.contains("TRY", ignoreCase = true) || firstText.contains("₺")
            val hasUSD = firstText.contains("USD", ignoreCase = true) || firstText.contains("$")
            val hasEUR = firstText.contains("EUR", ignoreCase = true) || firstText.contains("€")
            
            println("Contains JPY: $hasJPY (source currency)")
            println("Contains TRY: $hasTRY (first configured display target)")
            println("Contains USD: $hasUSD")
            println("Contains EUR: $hasEUR")
            
            println("\nEXPECTED BEHAVIOR:")
            println("- Initial unadopted query should convert to configured targets (TRY first)")
            println("- R1 fix made source appear first globally")
            println("\nACTUAL BEHAVIOR:")
            when {
                hasJPY -> println("✗ REGRESSION: First result is JPY (source) - breaks original behavior")
                hasTRY -> println("✓ CORRECT: First result is TRY (configured target)")
                else -> println("? UNEXPECTED: First result is neither JPY nor TRY")
            }
            println("==================")
        } else {
            println("ERROR: No currency results visible")
        }
        
        // This test RECORDS the observation, doesn't assert yet
        // We need to see if currency results even appear in Robolectric
    }
    
    @Test
    fun `BOUNDARY PREMISE - adoption then continuation preserves adopted currency`() {
        // After adopting a result like "3250 try", appending "/2" should stay in TRY
        
        // Simulate already-adopted state by directly setting field
        typeIntoField("3250 try")
        settle()
        
        // Check if currency results appear
        val currencyRowsContainer = overlay.currencyRowsContainer
        println("After typing '3250 try':")
        println("Currency rows: ${currencyRowsContainer.childCount}")
        
        if (currencyRowsContainer.childCount > 0) {
            // Now append arithmetic
            typeIntoField("3250 try / 2")
            settle()
            
            println("After appending '/ 2':")
            println("Currency rows: ${currencyRowsContainer.childCount}")
            
            if (currencyRowsContainer.childCount > 0) {
                val firstRow = currencyRowsContainer.getChildAt(0)
                println("First row: ${firstRow}")
                
                // QUESTION: Does first row show 1625 TRY or conversion to other currency?
                println("=== OBSERVATION ===")
                println("Query: '3250 try / 2'")
                println("Expected: First result 1625 TRY (preserve adopted unit)")
                println("Actual: [CHECK MANUALLY]")
                println("==================")
            }
        } else {
            println("ERROR: No currency results - async not working in test")
        }
    }
}
