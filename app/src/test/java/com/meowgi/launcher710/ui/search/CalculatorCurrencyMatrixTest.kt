package com.meowgi.launcher710.ui.search

import android.content.Context
import android.view.View
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
import org.robolectric.annotation.Config

/**
 * Comprehensive matrix testing calculator and currency interactions with Q25 letter-digit mapping.
 * 
 * Tests that:
 * - Currency ISO codes and aliases are recognized BEFORE letter-digit mapping
 * - Calculator letter-digit mapping works correctly for pure math
 * - Currency arithmetic works with both ISO codes and aliases
 * - No garbage output like "100)2," appears in calculator row
 * - Enter adoption and chaining work correctly
 * - Non-math words don't trigger calc/currency rows
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorCurrencyMatrixTest {
    
    private lateinit var context: Context
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: EditText
    private var fakeRatesCache: FakeCurrencyRatesCache? = null
    private val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

    data class TestCase(
        val input: String,
        val expectCalc: String?,  // Expected calculator result, or null if no calc row
        val expectCurrency: Boolean,  // Whether currency rows should appear
        val enterAdopts: String? = null,  // What Enter should adopt (calc priority over currency)
        val calcPriority: Boolean = false,  // If true, test that calc row is prioritized for Enter
        val description: String = ""
    )

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
        overlay = activity.findViewById(R.id.searchOverlay)
        field = activity.findViewById(R.id.searchInputInBar)
        overlay.testRatesCache = fakeRatesCache
        overlay.testSynchronousUpdate = true
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
    }

    private fun settle() {
        Thread.sleep(100)
        val mainLooper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        mainLooper.runToEndOfTasks()
        Thread.sleep(50)
        mainLooper.idle()
    }

    private fun findViewByTag(parent: View, tag: String): View? {
        if (parent.tag == tag) return parent
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.tag == tag) return child
                val found = findViewByTag(child, tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun pressEnter() {
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        settle()
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        settle()
    }

    private fun testCase(tc: TestCase) {
        // Set the actual test input (setText triggers text change listeners which call search)
        field.setText(tc.input)
        field.setSelection(tc.input.length)
        settle()
        
        val calcRow = findViewByTag(overlay, "calculatorRow")
        val calcVisible = calcRow != null && calcRow.visibility == View.VISIBLE
        val currVisible = overlay.currencyRowsContainer.visibility == View.VISIBLE
        
        // Debug plain number failures
        if ((tc.input == "w" || tc.input == "w00" || tc.input == "s00" || tc.input == "-4185") && (calcVisible != (tc.expectCalc != null))) {
            val expr = com.meowgi.launcher710.util.Calculator.toExpression(tc.input)
            println("DEBUG '${tc.input}': expect=${tc.expectCalc}, toExpression=$expr, calcVisible=$calcVisible")
        }
        
        // Check calculator row
        if (tc.expectCalc != null) {
            assertTrue("${tc.description}: Calculator row should be visible for '${tc.input}'", calcVisible)
            // Verify no garbage characters like ), in the result when not in input
            if (calcRow != null) {
                val calcText = findCalculatorText(calcRow)
                if (calcText != null && !tc.input.contains("(") && !tc.input.contains(")")) {
                    assertFalse("${tc.description}: Calculator should not show garbage ')' for '${tc.input}'",
                        calcText.contains(")"))
                }
            }
        } else {
            if (calcVisible) {
                fail("${tc.description}: Calculator row should NOT be visible for '${tc.input}'")
            }
        }
        
        // Check currency rows
        if (tc.expectCurrency) {
            assertTrue("${tc.description}: Currency rows should be visible for '${tc.input}'", currVisible)
        } else {
            if (currVisible) {
                fail("${tc.description}: Currency rows should NOT be visible for '${tc.input}'")
            }
        }
        
        // Test Enter adoption priority if specified
        if (tc.enterAdopts != null) {
            pressEnter()
            val adopted = field.text.toString()
            assertTrue("${tc.description}: Enter should adopt '${tc.enterAdopts}' but got '$adopted'",
                adopted.contains(tc.enterAdopts) || adopted == tc.enterAdopts)
        }
        
        // Test calculator priority: if both calc and currency visible, calc MUST be prioritized
        if (tc.calcPriority && calcVisible && currVisible) {
            pressEnter()
            val adopted = field.text.toString()
            // Adopted text should match calculator result, not currency conversion
            assertFalse("${tc.description}: When both calc and currency rows exist, Enter must adopt CALC first, not currency",
                adopted.contains("USD") && !tc.expectCalc!!.contains("USD"))
        }
    }
    
    private fun findCalculatorText(calcRow: View): String? {
        if (calcRow is android.widget.TextView) return calcRow.text?.toString()
        if (calcRow is android.view.ViewGroup) {
            for (i in 0 until calcRow.childCount) {
                val text = findCalculatorText(calcRow.getChildAt(i))
                if (text != null) return text
            }
        }
        return null
    }

    /**
     * COMPREHENSIVE TEST MATRIX
     * 
     * Categories:
     * 1. Pure calculator with letter-digits (Q25 keyboard)
     * 2. Currency ISO codes (JPY, TRY, USD, EUR, GBP, IDR)
     * 3. Currency aliases (yen, try, tl, lira, rupiah, dollar, euro, pound)
     * 4. Currency with arithmetic (mixed currency math)
     * 5. Amount formats (decimals, thousands, spaces)
     * 6. Non-math words (should show nothing)
     */
    @Test
    fun `comprehensive calculator and currency matrix`() {
        launchWithFakeRates()
        
        val testCases = listOf(
            // ===== PURE CALCULATOR WITH LETTER-DIGITS =====
            TestCase("w", null, false, description = "Single letter w (no math)"),
            TestCase("woe", "1+2", false, description = "Letter-digit calc: w+e = 1+2"),
            TestCase("eoe", "2+2", false, description = "Letter-digit calc: e+e = 2+2"),
            TestCase("w00", null, false, description = "Letter-digit: w00 = 100 (no operator, no calc)"),
            TestCase("w00oe00", "100+200", false, description = "Letter-digit calc: 100+200"),
            TestCase("woeoror", "1+2+3+3", false, description = "Multiple additions"),
            TestCase("eae", "2*2", false, description = "Letter multiplication: e*e = 2*2"),
            TestCase("r00as", "300*4", false, description = "Letter-digit: 300*4"),
            TestCase("wogr", "1/3", false, description = "Letter division: 1/3"),
            TestCase("sie", "4-2", false, description = "Letter subtraction: 4-2"),
            
            // ===== CURRENCY: ISO CODES (SHOULD WORK) =====
            TestCase("100 jpy", null, true, description = "Amount + ISO code"),
            TestCase("w00 jpy", null, true, description = "Letter-digit amount + ISO"),
            TestCase("100jpy", null, true, description = "Amount+ISO no space"),
            TestCase("jpy 100", null, true, description = "ISO + amount"),
            TestCase("JPY 100", null, true, description = "ISO uppercase + amount"),
            TestCase("100 JPY", null, true, description = "Amount + ISO uppercase"),
            TestCase("100 usd", null, true, description = "USD ISO"),
            TestCase("100 USD", null, true, description = "USD uppercase"),
            TestCase("100 try", null, true, description = "TRY ISO lowercase"),
            TestCase("100 TRY", null, true, description = "TRY ISO uppercase"),
            TestCase("100 eur", null, true, description = "EUR ISO"),
            TestCase("100 gbp", null, true, description = "GBP ISO"),
            TestCase("100 idr", null, true, description = "IDR ISO"),
            
            // ===== CURRENCY: ISO WITH CONVERSION =====
            TestCase("100 jpy to try", null, true, description = "JPY to TRY conversion"),
            TestCase("w00 jpy to try", null, true, description = "Letter-digit JPY to TRY"),
            TestCase("100 usd to eur", null, true, description = "USD to EUR"),
            TestCase("100 jpy in try", null, true, description = "JPY in TRY"),
            TestCase("100 jpy = try", null, false, description = "JPY = TRY (rejected - = operator ambiguous between calc and currency)"),
            
            // ===== CURRENCY: ALIASES (CURRENTLY BROKEN) =====
            TestCase("100 yen", null, true, description = "Amount + yen alias"),
            TestCase("w00 yen", null, true, description = "Letter-digit + yen (BROKEN: gets letter-mapped)"),
            TestCase("100 YEN", null, true, description = "YEN uppercase alias"),
            TestCase("100 Yen", null, true, description = "Yen mixed case"),
            TestCase("yen 100", null, true, description = "yen + amount"),
            TestCase("100yen", null, true, description = "Amount+yen no space"),
            
            TestCase("100 tl", null, true, description = "TRY alias: tl (BROKEN: t→(, l→?)"),
            TestCase("w00 tl", null, true, description = "Letter-digit + tl alias"),
            TestCase("100 TL", null, true, description = "TL uppercase"),
            TestCase("100 lira", null, true, description = "TRY alias: lira"),
            
            TestCase("100 rupiah", null, true, description = "IDR alias: rupiah"),
            TestCase("w00 rupiah", null, true, description = "Letter-digit + rupiah"),
            TestCase("100 RUPIAH", null, true, description = "RUPIAH uppercase"),
            
            TestCase("100 dollar", null, true, description = "USD alias: dollar"),
            TestCase("100 dollars", null, true, description = "USD alias: dollars (plural)"),
            TestCase("$100", null, true, description = "USD symbol prefix"),
            TestCase("100$", null, true, description = "USD symbol suffix"),
            
            TestCase("100 euro", null, true, description = "EUR alias: euro"),
            TestCase("€100", null, true, description = "EUR symbol prefix"),
            TestCase("100€", null, true, description = "EUR symbol suffix"),
            
            TestCase("100 pound", null, true, description = "GBP alias: pound"),
            TestCase("100 pounds", null, true, description = "GBP alias: pounds"),
            TestCase("£100", null, true, description = "GBP symbol prefix"),
            
            // ===== CURRENCY: ALIASES WITH CONVERSION =====
            TestCase("100 yen to try", null, true, description = "yen to TRY (BROKEN)"),
            TestCase("w00 yen to try", null, true, description = "Letter-digit yen to TRY (BROKEN)"),
            TestCase("100 yen to tl", null, true, description = "yen to tl (both aliases BROKEN)"),
            TestCase("100 dollar to euro", null, true, description = "dollar to euro aliases"),
            TestCase("100 pound to yen", null, true, description = "pound to yen aliases"),
            
            // ===== CURRENCY: ARITHMETIC (CALC + CURRENCY PRIORITY TESTING) =====
            TestCase("100 yen * 10", "1000", true, enterAdopts = "1000", calcPriority = true,
                description = "Currency arithmetic: 100 yen * 10 → calc shows 1000 JPY, Enter adopts calc"),
            TestCase("100 yen / 10", "10", true, enterAdopts = "10", calcPriority = true,
                description = "Currency arithmetic: 100 yen / 10 → calc shows 10 JPY, Enter adopts calc"),
            TestCase("100 yen + 50", "150", true, enterAdopts = "150", calcPriority = true,
                description = "Currency arithmetic: addition → calc priority"),
            TestCase("100 yen - 50", "50", true, enterAdopts = "50", calcPriority = true,
                description = "Currency arithmetic: subtraction → calc priority"),
            TestCase("w00 yen a w0", "1000", true, enterAdopts = "1000", calcPriority = true,
                description = "Letter-digit yen * 10 (BROKEN) → should show 1000 JPY, Enter adopts calc"),
            TestCase("w00 yen g w0", "10", true, enterAdopts = "10", calcPriority = true,
                description = "Letter-digit yen / 10 (g=/) → calc priority"),
            TestCase("w00 jpy a w0", "1000", true, enterAdopts = "1000", calcPriority = true,
                description = "Letter-digit JPY * 10 (ISO protected) → calc priority"),
            TestCase("100 tl * 5", "500", true, enterAdopts = "500", calcPriority = true,
                description = "tl alias with arithmetic → calc priority"),
            TestCase("100 dollar / 2", "50", true, enterAdopts = "50", calcPriority = true,
                description = "dollar alias with division → calc priority"),
            TestCase("100 usd - 20", "80", true, enterAdopts = "80", calcPriority = true,
                description = "USD ISO with subtraction → calc priority"),
            TestCase("w00 tl + 50", "150", true, enterAdopts = "150", calcPriority = true,
                description = "Letter-digit tl + 50 → calc priority"),
            TestCase("50 euro * 2", "100", true, enterAdopts = "100", calcPriority = true,
                description = "euro alias with multiplication → calc priority"),
            TestCase("200 pound / 4", "50", true, enterAdopts = "50", calcPriority = true,
                description = "pound alias with division → calc priority"),
            
            // ===== AMOUNT FORMATS =====
            TestCase("1000 yen", null, true, description = "Thousands no separator"),
            TestCase("1,000 yen", null, true, description = "Thousands with comma separator"),
            TestCase("1.000 yen", null, true, description = "Thousands with period separator (EU)"),
            TestCase("1 000 yen", null, true, description = "Thousands with space separator"),
            TestCase("100.5 yen", null, true, description = "Decimal with period"),
            TestCase("100,5 yen", null, true, description = "Decimal with comma (EU)"),
            TestCase("0.01 usd", null, true, description = "Small decimal amount"),
            TestCase("1000000 yen", null, true, description = "Large amount no separator"),
            
            // ===== MIXED CASE AND SPACING =====
            TestCase("100YEN", null, true, description = "No space uppercase alias"),
            TestCase("100yen", null, true, description = "No space lowercase alias"),
            TestCase("100  yen", null, true, description = "Multiple spaces"),
            TestCase("  100 yen  ", null, true, description = "Leading/trailing spaces"),
            
            // ===== NON-MATH WORDS (SHOULD SHOW NOTHING) =====
            TestCase("yen", null, false, description = "Just currency alias alone"),
            TestCase("try", null, false, description = "Ambiguous: TRY code or verb 'try'"),
            TestCase("try again", null, false, description = "Phrase with 'try'"),
            TestCase("tl;dr", null, false, description = "Internet slang with tl"),
            TestCase("w00t", null, false, description = "Internet slang (not math)"),
            TestCase("hello world", null, false, description = "Normal words"),
            TestCase("w", null, false, description = "Single letter"),
            TestCase("wee", null, false, description = "Word (not w+e+e math)"),
            TestCase("lol", null, false, description = "Chat speak"),
            TestCase("year", null, false, description = "Word containing 'yen' substring"),
            TestCase("trial", null, false, description = "Word containing 'try' substring"),
            
            // ===== NEGATIVE AMOUNTS (MUST WORK) =====
            TestCase("-100 yen", null, true, description = "Negative amount: -100 yen"),
            TestCase("-100 jpy", null, true, description = "Negative amount: -100 JPY ISO"),
            TestCase("-4185 jpy", null, true, description = "Negative amount: -4185 JPY (from calc adoption)"),
            TestCase("-4185jpy", null, true, description = "Negative amount no space: -4185jpy"),
            TestCase("- 4185 yen", null, true, description = "Negative with space: - 4185 yen"),
            TestCase("-w00 tl", null, true, description = "Negative letter-digit: -w00 tl = -100 TL"),
            TestCase("-100 usd to try", null, true, description = "Negative conversion: -100 USD to TRY"),
            TestCase("-100 yen to usd", null, true, description = "Negative yen to USD"),
            TestCase("-50 euro * 2", "-100", true, enterAdopts = "-100", calcPriority = true,
                description = "Negative currency arithmetic: -50 euro * 2 = -100 EUR"),
            TestCase("-100 dollar / 2", "-50", true, enterAdopts = "-50", calcPriority = true,
                description = "Negative currency division: -100 dollar / 2"),
            
            // Unicode minus (U+2212)
            TestCase("−100 yen", null, true, description = "Unicode minus: −100 yen"),
            TestCase("−100 jpy", null, true, description = "Unicode minus ISO: −100 JPY"),
            
            // Negative from calculator adoption flow (the reported bug)
            // User does: 4650 / 100 * 10 - 4650 → adopts -4185 → should show currency
            TestCase("-4185", null, false, description = "Just negative number (no currency)"),
            
            // ===== EDGE CASES =====
            TestCase("0 yen", null, true, description = "Zero amount"),
            TestCase("0.00 usd", null, true, description = "Zero with decimals"),
            TestCase("100yen*", null, false, description = "Trailing operator (invalid)"),
            TestCase("*100 yen", null, false, description = "Leading operator (invalid)"),
            TestCase("100 yen yen", null, false, description = "Duplicate currency"),
            TestCase("100 yen to yen", null, false, description = "Same currency conversion (rejected - simpler than showing identity)"),
            TestCase("100 jpy 200 usd", null, false, description = "Two amounts (ambiguous)"),
            
            // ===== PURE CALCULATOR (NO CURRENCY) =====
            TestCase("2+2", "4", false, description = "Simple addition"),
            TestCase("100-50", "50", false, description = "Subtraction"),
            TestCase("10*5", "50", false, description = "Multiplication"),
            TestCase("100/4", "25", false, description = "Division"),
            TestCase("(2+3)*4", "20", false, description = "Parentheses"),
            TestCase("2.5*4", "10", false, description = "Decimal math"),
            TestCase("1,000+500", "1500", false, description = "Thousands separator in calc"),
            
            // ===== ADDITIONAL Q25 LETTER-DIGIT COMBOS =====
            TestCase("eae5", "2*24", false, description = "Mixed letter-digit operators"),
            TestCase("w0ie", "10-2", false, description = "Letter-digit subtraction"),
            TestCase("rt4)", null, false, description = "Letter with parenthesis: 3(4) requires implicit multiplication - not supported"),
            TestCase("s00", null, false, description = "s00 = 400 (no operator)"),
            TestCase("d00", null, false, description = "d00 = 500 (no operator)"),
            TestCase("f00", null, false, description = "f00 = 600 (no operator)"),
            TestCase("z00", null, false, description = "z00 = 700 (no operator)"),
            TestCase("x00", null, false, description = "x00 = 800 (no operator)"),
            TestCase("c00", null, false, description = "c00 = 900 (no operator)")
        )
        
        var passCount = 0
        var failCount = 0
        val failures = mutableListOf<String>()
        
        for (tc in testCases) {
            try {
                testCase(tc)
                passCount++
            } catch (e: AssertionError) {
                failCount++
                failures.add("${tc.description}: ${e.message}")
            }
        }
        
        // Report results
        val total = testCases.size
        println("=" * 80)
        println("CALCULATOR/CURRENCY MATRIX TEST RESULTS")
        println("=" * 80)
        println("Total test cases: $total")
        println("PASSED (GREEN): $passCount")
        println("FAILED (RED):   $failCount")
        println()
        
        if (failCount > 0) {
            println("FAILURES:")
            failures.forEach { println("  - $it") }
            println()
            fail("$failCount of $total test cases failed. See output above for details.")
        }
    }
}

operator fun String.times(n: Int) = this.repeat(n)
