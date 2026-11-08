package com.meowgi.launcher710.integration

import android.Manifest
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ai.AiConfig
import com.meowgi.launcher710.ai.AiConfigResult
import com.meowgi.launcher710.ai.AiEvent
import com.meowgi.launcher710.ai.AiCall
import com.meowgi.launcher710.ai.AiState
import com.meowgi.launcher710.ui.search.SearchOverlay
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import com.meowgi.launcher710.voice.FakeSpeechRecognizer
import com.meowgi.launcher710.voice.FakeSpeechRecognizerFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for voice+AI progress indicator overlap scenarios.
 * Verifies unified progress indicator correctly tracks both operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAiProgressOverlapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val aiReady = AiConfigResult.Ready(AiConfig("https://x/v1", "sk-test", "m", ""))
    private val aiRelease = CountDownLatch(1)
    private val lastAiCall = AtomicReference<AiCall>()
    
    private lateinit var activity: LauncherActivity
    private lateinit var prefs: LauncherPrefs
    private lateinit var searchField: EditText
    private lateinit var overlay: SearchOverlay
    private lateinit var fakeRecognizer: FakeSpeechRecognizer
    private lateinit var progressIndicator: android.widget.ProgressBar

    @Before
    fun setup() {
        // Grant microphone permission
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        
        prefs = LauncherPrefs(context)
        prefs.voiceSearchEnabled = true
        prefs.voiceSearchLongPress0 = true
        
        activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        looper.idle()
        
        searchField = activity.findViewById(R.id.searchInputInBar)
        overlay = activity.findViewById(R.id.searchOverlay)
        progressIndicator = activity.findViewById(R.id.progressIndicator)
        
        // Inject fake recognizer for voice testing
        fakeRecognizer = FakeSpeechRecognizer()
        val fakeFactory = FakeSpeechRecognizerFactory(fakeRecognizer)
        overlay.speechRecognizerFactory = fakeFactory
        
        // Configure fake AI transport
        overlay.repository = AppRepository.get(context)
        overlay.aiConfigProvider = { aiReady }
        overlay.aiSession.streamer = { _, _, call, emit ->
            lastAiCall.set(call)
            aiRelease.await(5, TimeUnit.SECONDS)
            if (!call.isCancelled) {
                emit(AiEvent.Token("AI response"))
                emit(AiEvent.Done)
            }
        }
        
        // Open search overlay
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        looper.idle()
        assertEquals("search visible", View.VISIBLE, overlay.visibility)
    }

    private fun settle() {
        looper.idleFor(Duration.ofMillis(300))
        repeat(50) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    @Test
    fun aiFinishesWhileVoiceListeningKeepsProgressVisible() {
        // Start AI
        searchField.setText("test query")
        settle()
        assertTrue("AI started", overlay.startAiAnswer())
        settle()
        assertEquals("progress visible for AI", View.VISIBLE, progressIndicator.visibility)
        assertEquals("AI loading", AiState.Loading::class.java, overlay.aiSession.state::class.java)
        
        // Complete AI
        aiRelease.countDown()
        settle()
        // Progress hidden after AI completes
        assertEquals("progress hidden after AI done", View.GONE, progressIndicator.visibility)
        
        // Start voice dictation
        searchField.setText("")
        settle()
        assertTrue("voice started", overlay.startVoiceDictation())
        settle()
        // Progress visible for voice
        assertEquals("progress visible for voice", View.VISIBLE, progressIndicator.visibility)
        
        // Voice ends
        fakeRecognizer.simulateEndOfSpeech()
        settle()
        // Progress hidden (both idle)
        assertEquals("progress hidden after voice end", View.GONE, progressIndicator.visibility)
    }

    @Test
    fun voiceEndWhileAiLoadingKeepsProgressVisible() {
        // Start voice
        searchField.setText("")
        settle()
        assertTrue("voice started", overlay.startVoiceDictation())
        settle()
        assertEquals("progress visible for voice", View.VISIBLE, progressIndicator.visibility)
        
        // Voice partial result
        fakeRecognizer.simulatePartialResult("test")
        settle()
        
        // Start AI with voice text
        searchField.setText("test")
        settle()
        assertTrue("AI started", overlay.startAiAnswer())
        settle()
        // Progress still visible (both active)
        assertEquals("progress visible for voice+AI", View.VISIBLE, progressIndicator.visibility)
        
        // Voice ends (onEndOfSpeech)
        fakeRecognizer.simulateEndOfSpeech()
        settle()
        // Progress STAYS visible because AI still loading
        assertEquals("progress stays visible (AI still active)", View.VISIBLE, progressIndicator.visibility)
        
        // Complete AI
        aiRelease.countDown()
        settle()
        // Now progress hidden (both idle)
        assertEquals("progress hidden after both idle", View.GONE, progressIndicator.visibility)
    }

    @Test
    fun dismissCancelsBothVoiceAndAi() {
        // Start voice
        searchField.setText("")
        settle()
        assertTrue("voice started", overlay.startVoiceDictation())
        settle()
        assertEquals("progress visible for voice", View.VISIBLE, progressIndicator.visibility)
        
        // Voice partial
        fakeRecognizer.simulatePartialResult("test")
        settle()
        
        // Start AI
        searchField.setText("test")
        settle()
        assertTrue("AI started", overlay.startAiAnswer())
        settle()
        assertEquals("progress visible for both", View.VISIBLE, progressIndicator.visibility)
        
        // Dismiss overlay (cancels both)
        overlay.dismiss()
        settle()
        // Progress hidden (both cancelled)
        assertEquals("progress hidden after dismiss", View.GONE, progressIndicator.visibility)
        assertEquals("AI cancelled", AiState.Idle::class.java, overlay.aiSession.state::class.java)
        
        aiRelease.countDown()
    }
}
