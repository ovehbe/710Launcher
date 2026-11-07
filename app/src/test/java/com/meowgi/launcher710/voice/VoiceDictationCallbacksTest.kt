package com.meowgi.launcher710.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
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
import org.robolectric.shadows.ShadowApplication

/**
 * Tests for voice dictation callback semantics: partial/final results,
 * prefix preservation, manual edit cancellation, and session tracking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceDictationCallbacksTest {

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
        
        // Open search overlay
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        looper.idle()
        assertEquals("search visible", android.view.View.VISIBLE, searchOverlay.visibility)
    }

    @Test
    fun partialResultUpdatesSearchField() {
        // Start voice from empty search
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Simulate partial result
        fakeRecognizer.simulatePartialResult("hello")
        looper.idle()
        
        assertEquals("partial result shown in field", "hello", searchField.text.toString())
    }

    @Test
    fun finalResultUpdatesSearchField() {
        // Start voice from empty search
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Simulate final result
        fakeRecognizer.simulateFinalResult("world")
        looper.idle()
        
        assertEquals("final result shown in field", "world", searchField.text.toString())
    }

    @Test
    fun prefixPreservedWithPartialResults() {
        // Type 'app ' prefix
        searchField.setText("app ")
        looper.idle()
        
        // Start voice
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Simulate partial results
        fakeRecognizer.simulatePartialResult("cal")
        looper.idle()
        assertEquals("prefix + partial", "app cal", searchField.text.toString())
        
        fakeRecognizer.simulatePartialResult("calend")
        looper.idle()
        assertEquals("prefix + updated partial", "app calend", searchField.text.toString())
    }

    @Test
    fun prefixPreservedWithFinalResult() {
        // Type 'app ' prefix
        searchField.setText("app ")
        looper.idle()
        
        // Start voice
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Simulate partial then final
        fakeRecognizer.simulatePartialResult("cal")
        looper.idle()
        assertEquals("prefix + partial", "app cal", searchField.text.toString())
        
        fakeRecognizer.simulateFinalResult("calendar")
        looper.idle()
        assertEquals("prefix + final", "app calendar", searchField.text.toString())
    }

    @Test
    fun manualEditCancelsVoiceOwnership() {
        // Start voice
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        fakeRecognizer.simulatePartialResult("test")
        looper.idle()
        assertEquals("partial result shown", "test", searchField.text.toString())
        
        // Manually type (simulate user editing)
        searchField.setText("test manual")
        looper.idle()
        
        // Late voice callback should be ignored
        fakeRecognizer.simulatePartialResult("ignored")
        looper.idle()
        
        assertEquals("manual edit preserved, late voice ignored", "test manual", searchField.text.toString())
    }

    @Test
    fun emptyFinalResultIgnored() {
        // Start voice
        searchField.setText("prefix ")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Empty final result
        fakeRecognizer.simulateFinalResult("")
        looper.idle()
        
        // Should keep prefix, not clear it
        assertEquals("prefix preserved on empty final", "prefix ", searchField.text.toString())
    }

    @Test
    fun cancelStopsRecognizer() {
        // Start voice
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        assertEquals("started once", 1, fakeRecognizer.startListenCallCount)
        
        // Cancel
        searchOverlay.cancelVoiceDictation()
        looper.idle()
        
        assertEquals("cancel called", 1, fakeRecognizer.cancelCallCount)
        assertFalse("recognizer not listening", fakeRecognizer.isListening)
    }

    @Test
    fun staleCallbacksIgnoredAfterCancel() {
        // Start voice
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Cancel immediately
        searchOverlay.cancelVoiceDictation()
        looper.idle()
        
        // Stale callback arrives
        fakeRecognizer.simulatePartialResult("stale")
        looper.idle()
        
        // Field should remain empty
        assertEquals("stale callback ignored", "", searchField.text.toString())
    }

    @Test
    fun sequentialVoiceSessionsIncrementSessionId() {
        // First session
        searchField.setText("")
        looper.idle()
        
        assertTrue("first voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        fakeRecognizer.simulateFinalResult("first")
        looper.idle()
        assertEquals("first result", "first", searchField.text.toString())
        
        // Clear and start second session
        searchField.setText("")
        looper.idle()
        
        assertTrue("second voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        fakeRecognizer.simulateFinalResult("second")
        looper.idle()
        assertEquals("second result", "second", searchField.text.toString())
        
        // Both sessions should have incremented startListenCallCount
        assertEquals("voice started twice", 2, fakeRecognizer.startListenCallCount)
    }

    @Test
    fun errorCallbackResetsState() {
        // Start voice
        searchField.setText("")
        looper.idle()
        
        assertTrue("voice started", searchOverlay.startVoiceDictation())
        looper.idle()
        
        // Simulate error
        fakeRecognizer.simulateError(android.speech.SpeechRecognizer.ERROR_NETWORK)
        looper.idle()
        
        // Should still be able to start again
        assertTrue("can restart after error", searchOverlay.startVoiceDictation())
    }
}
