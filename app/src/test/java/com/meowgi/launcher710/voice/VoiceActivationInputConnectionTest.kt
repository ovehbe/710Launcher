package com.meowgi.launcher710.voice

import android.Manifest
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ui.search.SearchOverlay
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Integration tests proving activation-gesture input handling defects.
 * Tests use real InputConnection.commitText to simulate IME behavior during hold-0 gesture.
 * 
 * Expected: Activation '0' rejected, legitimate edits preserved, speech appears.
 * Actual (fcf513d): Timing-based protection has gaps, voice cancelled by late IME commits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceActivationInputConnectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    
    private lateinit var activity: LauncherActivity
    private lateinit var prefs: LauncherPrefs
    private lateinit var searchField: EditText
    private lateinit var searchOverlay: SearchOverlay
    private lateinit var inputConnection: InputConnection
    private lateinit var fakeRecognizer: FakeSpeechRecognizer

    @Before
    fun setup() {
        // Grant microphone permission for tests
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = shadowOf(application)
        shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)
        
        prefs = LauncherPrefs(context)
        prefs.voiceSearchEnabled = true
        prefs.voiceSearchLongPress0 = true
        
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        
        searchField = activity.findViewById(R.id.searchInputInBar)
        searchOverlay = activity.findViewById(R.id.searchOverlay)
        
        // Get real InputConnection from EditText
        val editorInfo = EditorInfo()
        inputConnection = searchField.onCreateInputConnection(editorInfo)!!
        
        // Inject fake recognizer
        fakeRecognizer = FakeSpeechRecognizer()
        searchOverlay.speechRecognizerFactory = FakeSpeechRecognizerFactory(fakeRecognizer)
    }
    
    /**
     * DEFECT: Late IME commit (50ms after activation) cancels voice ownership.
     * Expected: '0' rejected, speech "hello" appears.
     * Actual: Voice cancelled, speech ignored.
     */
    @Test
    fun lateIMECommit50msAfterActivation_shouldRejectZero_speechShouldAppear() {
        println("\n=== TEST: Late IME 50ms after activation ===")
        
        // Hold 0 from home
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        // Voice started
        assertEquals("Voice should start", 1, fakeRecognizer.startListenCallCount)
        assertEquals("Field initially empty", "", searchField.text.toString())
        
        // Process post{} that clears ignoreNextManualEdit
        looper.idle()
        
        // 50ms later: IME commits '0' via real InputConnection (simulating Q25 delay)
        looper.idleFor(50, TimeUnit.MILLISECONDS)
        println("Committing '0' via InputConnection at T+550ms")
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after IME commit: '${searchField.text}'")
        
        // Send speech recognition result
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        // EXPECTED: "hello" (activation '0' rejected, speech appears)
        // ACTUAL: "" or "0" (voice cancelled by late IME, speech ignored)
        assertEquals("Speech should appear, activation 0 rejected", "hello", searchField.text.toString())
    }
    
    /**
     * DEFECT: Very late IME commit (300ms after activation) cancels voice.
     * Tests protection beyond 100ms cleanup window.
     */
    @Test
    fun lateIMECommit300msAfterActivation_shouldRejectZero_speechShouldAppear() {
        println("\n=== TEST: Late IME 300ms after activation ===")
        
        // Hold 0
        activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        looper.idle()
        
        // 300ms later: IME commits (well after 100ms cleanup)
        looper.idleFor(300, TimeUnit.MILLISECONDS)
        println("Committing '0' via InputConnection at T+800ms")
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after late IME: '${searchField.text}'")
        
        fakeRecognizer.simulatePartialResult("world")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        assertEquals("Speech should appear, late activation 0 rejected", "world", searchField.text.toString())
    }
    
    /**
     * Prefix preservation: User typed 'app', hold 0, IME commits '0', speech appends.
     * Expected: "apphello" (prefix preserved, activation '0' rejected, speech appends).
     */
    @Test
    fun prefixTypedBeforeActivation_shouldPreserve_speechShouldAppend() {
        println("\n=== TEST: Prefix 'app' + activation + speech ===")
        
        // User types 'app' before activation (using key events with proper timing for quick presses)
        val t0 = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(t0, t0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
        activity.dispatchKeyEvent(KeyEvent(t0, t0 + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 250, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, 0))
        looper.idle()
        
        // Verify search opened and text is there
        assertEquals("Search should be visible", android.view.View.VISIBLE, searchOverlay.visibility)
        assertTrue("Prefix 'app' typed", searchField.text.toString().contains("app"))
        
        println("Field before activation: '${searchField.text}'")
        val prefixBeforeActivation = searchField.text.toString()
        
        // Hold 0
        activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        assertEquals("Prefix preserved at activation", prefixBeforeActivation, searchField.text.toString())
        looper.idle()
        
        // IME commits '0' 100ms later
        looper.idleFor(100, TimeUnit.MILLISECONDS)
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after IME commit: '${searchField.text}'")
        
        // Speech arrives
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        // EXPECTED: prefix + "hello" (prefix + speech, no activation '0')
        val expected = prefixBeforeActivation + "hello"
        assertEquals("Prefix preserved, speech appended, no activation 0", expected, searchField.text.toString())
    }
    
    /**
     * Prefix with intentional zeros: '007' typed, hold 0, speech appends.
     * Expected: "007world" (intentional zeros preserved, no extra '0' from activation).
     */
    @Test
    fun prefixWithIntentionalZeros_shouldPreserve_speechShouldAppend() {
        println("\n=== TEST: Prefix '007' + activation + speech ===")
        
        // Type '007' using key events with proper timing (quick presses, not holds)
        val t0 = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(t0, t0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(t0, t0 + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_7, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 250, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_7, 0))
        looper.idle()
        
        assertEquals("Search should be visible", android.view.View.VISIBLE, searchOverlay.visibility)
        assertTrue("Prefix '007' typed", searchField.text.toString().contains("007"))
        
        println("Field before activation: '${searchField.text}'")
        val prefixBeforeActivation = searchField.text.toString()
        
        // Hold 0
        activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        assertEquals("Prefix '007' preserved", prefixBeforeActivation, searchField.text.toString())
        looper.idle()
        
        // IME commits '0' 150ms later
        looper.idleFor(150, TimeUnit.MILLISECONDS)
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after IME commit: '${searchField.text}'")
        
        fakeRecognizer.simulatePartialResult("world")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        val expected = prefixBeforeActivation + "world"
        assertEquals("Intentional zeros preserved, speech appended, no activation 0", expected, searchField.text.toString())
    }
    
    /**
     * Genuine user edit must cancel voice.
     * User holds 0, voice starts, then user types 'a' to cancel.
     * Expected: Field="a", voice cancelled.
     */
    @Test
    fun genuineUserEditAfterActivation_shouldCancelVoice_editPreserved() {
        println("\n=== TEST: Genuine user edit 'a' cancels voice ===")
        
        // Hold 0
        activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        looper.idle()
        
        // User types 'a' (genuine edit, wants to cancel voice)
        looper.idleFor(100, TimeUnit.MILLISECONDS)
        inputConnection.commitText("a", 1)
        looper.idle()
        
        println("Field after user types 'a': '${searchField.text}'")
        
        // Speech arrives (should be ignored, voice was cancelled)
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        // EXPECTED: "a" (user edit preserved, voice cancelled, speech ignored)
        assertEquals("User edit 'a' should cancel voice and be preserved", "a", searchField.text.toString())
    }
    
    /**
     * Deliberate 0 tap after activation (and after activation IME commit) should cancel voice.
     * Realistic scenario: activation IME commits first, THEN user types deliberately.
     * Expected: Second '0' preserved, voice cancelled.
     */
    @Test
    fun deliberateZeroTapAfterActivation_shouldCancelVoice_zeroPreserved() {
        println("\n=== TEST: Deliberate 0 tap (after activation commit) cancels voice ===")
        
        // Hold 0 to activate voice
        activity.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        looper.idle()
        
        // Realistic: IME commits '0' from activation gesture first (50ms later)
        looper.idleFor(50, TimeUnit.MILLISECONDS)
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after activation IME: '${searchField.text}'")
        // Should be "" (activation commit rejected)
        
        // THEN user deliberately types '0' again (150ms after that)
        looper.idleFor(150, TimeUnit.MILLISECONDS)
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after deliberate 0 tap: '${searchField.text}'")
        
        fakeRecognizer.simulatePartialResult("test")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        // EXPECTED: "0" (deliberate edit preserved, voice cancelled, speech ignored)
        assertEquals("Deliberate 0 tap should cancel voice", "0", searchField.text.toString())
    }
    
    /**
     * Normal short 0 press (not hold) should type '0' and NOT activate voice.
     * Expected: Field="0", voice NOT started.
     */
    @Test
    fun normalShortZeroPress_shouldTypeZero_voiceNotActivated() {
        println("\n=== TEST: Short 0 press types '0', no voice ===")
        
        // Quick tap 0 (not hold)
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        println("Field after short press: '${searchField.text}'")
        
        // EXPECTED: Field="0", voice NOT started
        assertTrue("Short press should type 0", searchField.text.toString().contains("0"))
        assertEquals("Voice should NOT start on short press", 0, fakeRecognizer.startListenCallCount)
    }
    
    /**
     * IME commit BEFORE activation completes should be preserved as intentional typing.
     * This test demonstrates that if user types during the hold, that text is preserved.
     * Expected: Voice still activates and appends to the early-typed character.
     */
    @Test
    fun imeCommitBeforeActivation_shouldPreserve_speechShouldAppend() {
        println("\n=== TEST: IME commit BEFORE activation completes ===")
        
        // User presses 0 down
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        
        // IME commits '0' quickly (within 100ms) - this is saved as the prefix
        looper.idleFor(100, TimeUnit.MILLISECONDS)
        inputConnection.commitText("0", 1)
        looper.idle()
        
        println("Field after early IME commit: '${searchField.text}'")
        
        // Continue holding until 500ms activation
        looper.idleFor(400, TimeUnit.MILLISECONDS)
        
        println("After timer: voice started=${fakeRecognizer.startListenCallCount}, field='${searchField.text}'")
        assertEquals("Voice should start", 1, fakeRecognizer.startListenCallCount)
        looper.idle()
        
        // Speech arrives
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        println("Field after speech: '${searchField.text}'")
        
        // EXPECTED: "0hello" (early '0' preserved in prefix, voice appends speech)
        // The early '0' was saved as key0PreHoldSearchText and restored as prefix
        assertEquals("Early '0' preserved as prefix, speech appends", "0hello", searchField.text.toString())
    }
    
    /**
     * Physical SHORT tap 0 as FIRST gesture after activation WITHOUT any late IME commit.
     * This is an intentional '0' that should cancel voice and be preserved.
     * Tests that hold-0 bypass doesn't swallow genuine tap-0 when no IME commit happens.
     */
    @Test
    fun physicalShortTap0AfterActivation_shouldCancelVoice_zeroPreserved() {
        println("\n=== TEST: Physical short tap 0 after activation (no late IME) ===")
        
        // Type "app" first
        val t0 = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(t0, t0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
        activity.dispatchKeyEvent(KeyEvent(t0, t0 + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 100, t0 + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, 0))
        looper.idle()
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0))
        activity.dispatchKeyEvent(KeyEvent(t0 + 200, t0 + 250, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P, 0))
        looper.idle()
        
        assertEquals("Prefix 'app' typed", "app", searchField.text.toString())
        
        // Hold 0 to activate voice
        val holdStart = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(holdStart, holdStart, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice should start", 1, fakeRecognizer.startListenCallCount)
        assertEquals("Prefix preserved at activation", "app", searchField.text.toString())
        
        // Release hold-0 (key-up after 500ms)
        activity.dispatchKeyEvent(KeyEvent(holdStart, holdStart + 500, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // NO IME commit happens (Q25 might not send one, or we're testing without it)
        // Now user physically taps 0 as a NEW gesture - short press via key events
        val tapStart = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(tapStart, tapStart, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(tapStart, tapStart + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        println("After physical tap 0: voice canceled=${fakeRecognizer.startListenCallCount == 1 && !searchOverlay.voiceIsOwner}, field='${searchField.text}'")
        
        // Voice should be canceled by this edit
        assertFalse("Voice should be cancelled by intentional 0", searchOverlay.voiceIsOwner)
        
        // Field should show prefix + intentional '0'
        assertEquals("Intentional '0' preserved after canceling voice", "app0", searchField.text.toString())
        
        // Speech arriving after cancellation should be ignored
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        assertEquals("Speech ignored after voice cancelled", "app0", searchField.text.toString())
    }
    
    /**
     * Privacy regression test: diagnostics must NOT contain raw user text or speech transcripts.
     * Uses distinct synthetic sentinels to trigger both rejection and genuine-edit paths.
     */
    @Test
    fun diagnosticsShouldNotContainUserData_privacyRegression() {
        println("\n=== TEST: Privacy regression - diagnostics sanitized ===")
        
        // Clear diagnostics
        com.meowgi.launcher710.ui.search.VoiceDiagnostics.clear()
        
        // Type synthetic sentinel prefix
        val sentinel = "PRIVACY_SENTINEL_12345"
        searchField.setText(sentinel)
        looper.idle()
        
        // Hold 0 to activate voice
        val holdStart = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(holdStart, holdStart, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(500, TimeUnit.MILLISECONDS)
        
        assertEquals("Voice started", 1, fakeRecognizer.startListenCallCount)
        looper.idle()
        
        // Simulate late IME commit that should be rejected
        inputConnection.commitText("0", 1)
        looper.idle()
        
        // Now trigger genuine edit with speech transcript sentinel
        val speechSentinel = "TRANSCRIPT_SENTINEL_67890"
        searchField.setText(speechSentinel)
        looper.idle()
        
        // Get diagnostic log
        val diagnostics = com.meowgi.launcher710.ui.search.VoiceDiagnostics.getDiagnosticLog(activity)
        
        println("Diagnostics length: ${diagnostics.length}")
        
        // Verify sentinels do NOT appear in diagnostics
        assertFalse("Diagnostics must not contain user prefix sentinel", diagnostics.contains(sentinel))
        assertFalse("Diagnostics must not contain speech transcript sentinel", diagnostics.contains(speechSentinel))
        
        // Verify diagnostic log contains expected safe markers (lengths, match boolean)
        assertTrue("Diagnostics should contain safe length markers", 
            diagnostics.contains("Len=") || diagnostics.contains("len=") || 
            diagnostics.contains("fieldLen=") || diagnostics.contains("expectedLen="))
    }
}
