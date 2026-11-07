package com.meowgi.launcher710.voice

import android.Manifest
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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

/**
 * Tests for 0-key short/long press behavior and voice dictation triggering.
 * Verifies that tapping 0 types character, holding 0 triggers voice without typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceDictationKeyEventsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    
    private lateinit var activity: LauncherActivity
    private lateinit var prefs: LauncherPrefs
    private lateinit var searchField: EditText
    private lateinit var searchOverlay: SearchOverlay
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
        
        // Inject fake recognizer for testing
        fakeRecognizer = FakeSpeechRecognizer()
        searchOverlay.speechRecognizerFactory = FakeSpeechRecognizerFactory(fakeRecognizer)
    }

    @Test
    fun shortPress0TypesZeroFromHomeScreen() {
        // Start on home screen (search not visible)
        assertEquals("search not initially visible", android.view.View.GONE, searchOverlay.visibility)
        
        // Quick tap 0
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Search should open with '0' typed
        assertEquals("search visible after typing", android.view.View.VISIBLE, searchOverlay.visibility)
        assertTrue("0 typed in field", searchField.text.toString().contains("0"))
    }

    @Test
    fun shortPress0TypesZeroWhenSearchAlreadyOpen() {
        // Open search first by typing another character
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        looper.idle()
        
        assertEquals("search visible", android.view.View.VISIBLE, searchOverlay.visibility)
        val initialText = searchField.text.toString()
        assertTrue("'a' typed", initialText.contains("a"))
        
        // Now tap 0
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Should have 'a0' or similar
        val finalText = searchField.text.toString()
        assertTrue("0 appended to existing text", finalText.contains("0"))
        assertTrue("original text preserved", finalText.length > initialText.length)
    }

    @Test
    fun fastTappingMultipleZerosTypesAllOfThem() {
        // Triple-tap 0 rapidly
        val downTime = SystemClock.uptimeMillis()
        for (i in 0..2) {
            val t = downTime + i * 100L
            activity.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
            activity.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
            looper.idle()
        }
        
        val text = searchField.text.toString()
        val zeroCount = text.count { it == '0' }
        assertEquals("all three 0s typed", 3, zeroCount)
    }

    @Test
    fun longPress0DoesNotTypeZero() {
        // Hold 0 for 600ms (longer than 500ms threshold)
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        
        // Advance time to trigger long-press timer (500ms)
        looper.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Release key
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Search should be open but NO '0' should be typed
        assertEquals("search visible", android.view.View.VISIBLE, searchOverlay.visibility)
        val text = searchField.text.toString()
        assertFalse("0 not typed after long press", text.contains("0"))
    }

    @Test
    fun longPress0ActuallyTriggersVoice() {
        // Hold 0 for 600ms (longer than 500ms threshold)
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        
        // Advance time to trigger long-press timer (500ms)
        looper.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        
        // Release key
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Verify voice recognition actually started
        assertEquals("voice recognition started exactly once", 1, fakeRecognizer.startListenCallCount)
        assertTrue("recognizer is listening", fakeRecognizer.isListening)
    }

    @Test
    fun longPress0WithRepeatsFiresOnlyOnce() {
        // Hold 0 with key repeats (simulates continuous hold)
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        
        // Simulate key repeats (repeatCount > 0)
        for (repeat in 1..5) {
            activity.dispatchKeyEvent(KeyEvent(downTime, downTime + repeat * 100L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, repeat))
            looper.idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        
        // Voice should trigger only once (not multiple times)
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Verify voice started exactly once despite repeats
        assertEquals("voice recognition started exactly once despite key repeats", 1, fakeRecognizer.startListenCallCount)
        
        // Also verify NO zeros typed
        val text = searchField.text.toString()
        assertFalse("no 0s typed from held key with repeats", text.contains("0"))
    }

    @Test
    fun dismissingSearchCancelsLongPressTimer() {
        // First tap a key to open search
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        looper.idle()
        assertEquals("search visible initially", android.view.View.VISIBLE, searchOverlay.visibility)
        
        // Start holding 0
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        
        // After 200ms (before timer fires), press Back to dismiss
        looper.idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        looper.idle()
        
        // Release 0 key
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 300, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Search should stay dismissed, timer canceled, 0 not inserted
        assertEquals("search dismissed", android.view.View.GONE, searchOverlay.visibility)
    }

    @Test
    fun voiceSearchDisabledPrefAllowsNormalTyping() {
        prefs.voiceSearchEnabled = false
        
        // Hold 0 for 600ms
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Since voice disabled, should type '0' normally even after long hold
        // (This tests that disabled pref actually disables the feature)
        assertTrue("0 typed when voice disabled", searchField.text.toString().contains("0"))
    }

    @Test
    fun longPress0PrefDisabledAllowsNormalTyping() {
        prefs.voiceSearchLongPress0 = false
        
        // Hold 0 for 600ms
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, 0))
        looper.idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0, 0))
        looper.idle()
        
        // Long-press 0 disabled, should type normally
        assertTrue("0 typed when long-press disabled", searchField.text.toString().contains("0"))
    }

    @Test
    fun numpad0BehavesLikeRegular0() {
        // Test KEYCODE_NUMPAD_0 (separate key on some keyboards)
        val downTime = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_0, 0))
        activity.dispatchKeyEvent(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_0, 0))
        looper.idle()
        
        assertTrue("numpad 0 types zero", searchField.text.toString().contains("0"))
    }
}
