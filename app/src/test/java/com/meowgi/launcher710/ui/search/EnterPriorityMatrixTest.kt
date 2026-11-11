package com.meowgi.launcher710.ui.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.FakeCurrencyRatesCache
import com.meowgi.launcher710.util.LauncherPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Test matrix for Enter key priority with calculator/currency vs real results.
 * 
 * Priority rules:
 * 1. If any real result exists (app, contact, Google suggestion, etc.), Enter opens that result
 * 2. Only when NO other results does Enter adopt calculator/currency
 * 3. Explicit selection (trackpad/tap) of calculator/currency row still gets adopted
 * 4. Chaining after adoption still works when no competing results
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterPriorityMatrixTest {
    
    private lateinit var activity: LauncherActivity
    private lateinit var overlay: SearchOverlay
    private lateinit var field: android.widget.EditText
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Fake launcher apps so "dis" / "calculator" produce real search hits in Robolectric.
        registerFakeLauncherApp(
            context,
            packageName = "com.discord",
            activityName = "com.discord.MainActivity",
            label = "Discord"
        )
        registerFakeLauncherApp(
            context,
            packageName = "com.android.calculator2",
            activityName = "com.android.calculator2.Calculator",
            label = "Calculator"
        )
        
        // Enable calculator and currency features, disable web suggestions
        LauncherPrefs(context).apply {
            searchCalculatorEnabled = true
            searchCurrencyEnabled = true
            setSearchCurrencyList(listOf("USD", "EUR", "JPY", "GBP"))
            searchWebSuggestions = false  // Disable Google web suggestions for pure test
        }

        // Ensure AppRepository sees the newly registered packages (singleton may already be warm).
        val repo = AppRepository.get(context)
        repo.invalidate()
        runBlocking { repo.loadApps() }
        
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        overlay = activity.findViewById(com.meowgi.launcher710.R.id.searchOverlay)
        field = activity.findViewById(com.meowgi.launcher710.R.id.searchInputInBar)
        
        // Set up fake currency rates for currency conversion tests
        overlay.testRatesCache = FakeCurrencyRatesCache()
        overlay.testSynchronousUpdate = true
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("AI off") }
        
        // Wait for initial setup / type-to-search posts
        settle()
    }
    
    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.finish()
    }

    private fun registerFakeLauncherApp(
        context: Context,
        packageName: String,
        activityName: String,
        label: String
    ) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            name = packageName
            flags = ApplicationInfo.FLAG_INSTALLED
            nonLocalizedLabel = label
            sourceDir = "/data/app/$packageName"
            publicSourceDir = "/data/app/$packageName"
            icon = android.R.drawable.sym_def_app_icon
        }
        val activityInfo = ActivityInfo().apply {
            name = activityName
            this.packageName = packageName
            applicationInfo = appInfo
            exported = true
            enabled = true
            nonLocalizedLabel = label
            icon = android.R.drawable.sym_def_app_icon
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = activityInfo
            nonLocalizedLabel = label
            icon = android.R.drawable.sym_def_app_icon
        }
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = appInfo
            activities = arrayOf(activityInfo)
            firstInstallTime = 1L
            lastUpdateTime = 1L
        }

        val shadowPm = shadowOf(context.packageManager)
        shadowPm.installPackage(packageInfo)
        // queryIntentActivities (AppRepository.loadApps) reads these resolve infos.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)
        shadowPm.addActivityIcon(ComponentName(packageName, activityName), ColorDrawable(0xFF5865F2.toInt()))
    }
    
    private fun settle() {
        Thread.sleep(50)
        val mainLooper = shadowOf(android.os.Looper.getMainLooper())
        mainLooper.runToEndOfTasks()
        Thread.sleep(50)
        mainLooper.idle()
        ShadowLooper.idleMainLooper()
    }

    private fun drainStarted() {
        while (shadowOf(activity).nextStartedActivity != null) { /* drain */ }
        while (shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity != null) { /* drain */ }
    }

    /** Latest started activity from the activity or application shadow. */
    private fun nextStarted(): Intent? =
        shadowOf(activity).nextStartedActivity
            ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    
    private fun pressEnter() {
        // Drain any previously started intents so we only see this Enter's launch.
        drainStarted()
        val queryBefore = field.text.toString()
        // Hardware Enter uses runQuery=false first; when nothing auto-activates (calc blocked by
        // real results), the field's editor-action path runs performExtendedSearch (runQuery=true).
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        settle()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        settle()
        // Only fall through to IME SEARCH when Enter neither adopted calc nor launched anything.
        // (Adoption changes the query and must not be followed by a search.)
        if (overlay.visibility == View.VISIBLE &&
            nextStarted() == null &&
            field.text.toString() == queryBefore
        ) {
            field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            settle()
        }
    }
    
    private fun hasCalculatorRow(): Boolean {
        val calcRow = overlay.findViewById<View?>(overlay.resources.getIdentifier("calculatorRow", "id", overlay.context.packageName))
            ?: findViewByTag(overlay, "calculatorRow")
        return calcRow?.visibility == View.VISIBLE
    }
    
    private fun findViewByTag(parent: View, tag: String): View? {
        if (parent.tag == tag) return parent
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                val found = findViewByTag(parent.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }
    
    data class TestCase(
        val input: String,
        val expectCalcRow: Boolean,
        val expectAppResult: Boolean,
        val enterShouldAdoptCalc: Boolean,
        val description: String,
        val expectedLaunchPackage: String? = null
    )
    
    @Test
    fun `Enter priority comprehensive matrix`() {
        val testCases = listOf(
            // ===== LETTER QUERY THAT MATCHES APP AND MAPS TO MATH =====
            TestCase(
                input = "dis",
                expectCalcRow = true,  // "dis" maps to "5-4" = 1
                expectAppResult = true,  // Discord app matches
                enterShouldAdoptCalc = false,  // Should open Discord, NOT adopt calculator
                description = "BUG FIX: 'dis' matches Discord, should open app not adopt calc '5-4=1'",
                expectedLaunchPackage = "com.discord"
            ),
            
            // ===== PURE MATH WITH NO APP MATCHES =====
            TestCase(
                input = "eoe",
                expectCalcRow = true,  // "eoe" maps to "2+2" = 4
                expectAppResult = false,  // No app matches "eoe"
                enterShouldAdoptCalc = true,  // Should adopt calculator result
                description = "Pure math 'eoe' (2+2) with no apps should adopt calc"
            ),
            TestCase(
                input = "woe",
                expectCalcRow = true,  // "woe" maps to "1+2" = 3
                expectAppResult = false,  // No app matches "woe"
                enterShouldAdoptCalc = true,  // Should adopt calculator result
                description = "Pure math 'woe' (1+2) with no apps should adopt calc"
            ),
            
            // ===== CURRENCY QUERIES =====
            TestCase(
                input = "100 usd to eur",
                expectCalcRow = false,  // No calculator row for currency conversion
                expectAppResult = false,  // No app matches
                enterShouldAdoptCalc = true,  // Should adopt currency result
                description = "Currency conversion with no apps should adopt currency"
            ),
            TestCase(
                input = "100 yen / w0",
                expectCalcRow = true,  // Currency arithmetic shows calculator
                expectAppResult = false,  // No app matches
                enterShouldAdoptCalc = true,  // Should adopt calculator "10 JPY"
                description = "Currency arithmetic with no apps should adopt calc with currency code"
            ),
            
            // ===== CHAINING AFTER ADOPTION =====
            // This is tested separately in existing adoption tests
            
            // ===== APP MATCH TAKES PRIORITY =====
            TestCase(
                input = "calculator",
                expectCalcRow = false,  // Word "calculator" doesn't map to math
                expectAppResult = true,  // Calculator app matches
                enterShouldAdoptCalc = false,  // Should open Calculator app
                description = "Typing 'calculator' should open Calculator app, not any calc row",
                expectedLaunchPackage = "com.android.calculator2"
            )
        )
        
        val failures = mutableListOf<String>()
        var passed = 0
        
        for (tc in testCases) {
            try {
                // Reset state
                field.setText("")
                settle()
                
                // Type the query
                field.setText(tc.input)
                field.setSelection(tc.input.length)
                settle()
                
                // Check current state
                val currentResultsField = overlay.javaClass.getDeclaredField("currentResults")
                currentResultsField.isAccessible = true
                val currentResults = currentResultsField.get(overlay) as List<*>
                val actualHasAppResults = currentResults.isNotEmpty()
                
                // Skip test cases that expect app results (can't test without installed apps)
                if (tc.expectAppResult && !actualHasAppResults) {
                    failures.add("${tc.description}: Expected app results for '${tc.input}' but none found after registering fake launchers")
                    continue
                }
                
                // Check calculator row visibility
                val calcVisible = hasCalculatorRow()
                if (calcVisible != tc.expectCalcRow) {
                    failures.add("${tc.description}: Calculator row visibility mismatch. Expected=${tc.expectCalcRow}, Actual=$calcVisible for '${tc.input}'")
                    continue
                }
                
                // Debug: Check what's causing hasRealResults to be true
                val suggestionsBoxField = overlay.javaClass.getDeclaredField("suggestionsBox")
                suggestionsBoxField.isAccessible = true
                val suggestionsBox = suggestionsBoxField.get(overlay) as View
                val suggestionsVisible = suggestionsBox.visibility == View.VISIBLE
                
                val resultsRecyclerField = overlay.javaClass.getDeclaredField("resultsRecycler")
                resultsRecyclerField.isAccessible = true
                val resultsRecycler = resultsRecyclerField.get(overlay) as View
                val recyclerVisible = resultsRecycler.visibility == View.VISIBLE
                
                // Check hasRealResults before Enter
                val hasRealBeforeEnter = try {
                    val method = overlay.javaClass.getDeclaredMethod("hasRealResults")
                    method.isAccessible = true
                    method.invoke(overlay) as Boolean
                } catch (e: Exception) {
                    false
                }
                
                // Press Enter
                val queryBeforeEnter = field.text.toString()
                pressEnter()
                val queryAfterEnter = field.text.toString()
                
                // Check if calculator was adopted (query changed) vs app opened (activity started)
                val calcAdopted = queryAfterEnter != queryBeforeEnter && overlay.visibility == View.VISIBLE
                val started = nextStarted()
                
                if (!tc.expectAppResult && tc.expectCalcRow && tc.enterShouldAdoptCalc) {
                    println("DEBUG '${tc.input}': hasRealResults=$hasRealBeforeEnter, calcAdopted=$calcAdopted, before='$queryBeforeEnter', after='$queryAfterEnter'")
                }
                if (tc.expectAppResult) {
                    println("DEBUG APP '${tc.input}': hasRealResults=$hasRealBeforeEnter, calcAdopted=$calcAdopted, started=$started, overlay=${overlay.visibility}, before='$queryBeforeEnter', after='$queryAfterEnter', results=${currentResults.size}")
                }
                
                val caseFailures = mutableListOf<String>()
                if (tc.enterShouldAdoptCalc) {
                    // Should have adopted calculator/currency
                    if (!calcAdopted) {
                        caseFailures.add("${tc.description}: Expected calculator/currency adoption for '${tc.input}' but query didn't change. Before='$queryBeforeEnter', After='$queryAfterEnter', hasRealResults=$hasRealBeforeEnter, suggestionsVisible=$suggestionsVisible, recyclerVisible=$recyclerVisible, currentResults.size=${currentResults.size}")
                    }
                } else {
                    // Should have opened app or performed search (NOT adopted calculator)
                    if (calcAdopted) {
                        caseFailures.add("${tc.description}: Should NOT have adopted calculator for '${tc.input}'. Query changed from '$queryBeforeEnter' to '$queryAfterEnter', hasRealResults=$hasRealBeforeEnter, suggestionsVisible=$suggestionsVisible, recyclerVisible=$recyclerVisible, currentResults.size=${currentResults.size}")
                    }
                    if (tc.expectAppResult) {
                        if (started == null) {
                            caseFailures.add("${tc.description}: Expected app launch intent for '${tc.input}' but none was started")
                        } else if (tc.expectedLaunchPackage != null &&
                            started.component?.packageName != tc.expectedLaunchPackage
                        ) {
                            caseFailures.add("${tc.description}: Expected launch package '${tc.expectedLaunchPackage}' but got '${started.component}'")
                        }
                    }
                }
                failures.addAll(caseFailures)
                if (caseFailures.isEmpty()) passed++
            } catch (e: Exception) {
                failures.add("${tc.description}: Exception - ${e.message}")
            }
        }
        
        println("\n" + "=".repeat(70))
        println("ENTER PRIORITY MATRIX TEST RESULTS")
        println("=".repeat(70))
        println("Total test cases: ${testCases.size}")
        println("PASSED: $passed")
        println("FAILED: ${failures.size}")
        
        if (failures.isNotEmpty()) {
            println("\nFAILURES:")
            failures.forEach { println("  - $it") }
        }
        println("=".repeat(70))
        
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${testCases.size} test cases failed. See output above for details.")
        }
    }
}
