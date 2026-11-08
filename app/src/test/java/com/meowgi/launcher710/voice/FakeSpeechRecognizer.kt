package com.meowgi.launcher710.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.meowgi.launcher710.ui.search.SpeechRecognizerFactory
import com.meowgi.launcher710.ui.search.SpeechRecognizerWrapper

/**
 * Fake SpeechRecognizer for testing voice dictation without real hardware.
 * Allows tests to control when callbacks fire and what results are returned.
 */
class FakeSpeechRecognizer : SpeechRecognizerWrapper {
    var listener: RecognitionListener? = null
    var isListening = false
        private set
    var startListenCallCount = 0
        private set
    var lastStartIntent: Intent? = null
    var cancelCallCount = 0
        private set
    var stopListenCallCount = 0
        private set
    
    override fun setRecognitionListener(listener: RecognitionListener?) {
        this.listener = listener
    }

    override fun startListening(intent: Intent) {
        lastStartIntent = intent
        isListening = true
        startListenCallCount++
        listener?.onReadyForSpeech(Bundle.EMPTY)
    }

    override fun stopListening() {
        stopListenCallCount++
        isListening = false
    }

    override fun cancel() {
        cancelCallCount++
        isListening = false
    }

    override fun destroy() {
        isListening = false
        listener = null
    }
    
    // Test helper methods to simulate recognition events
    fun simulatePartialResult(text: String) {
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        listener?.onPartialResults(bundle)
    }
    
    fun simulateFinalResult(text: String) {
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        listener?.onResults(bundle)
        isListening = false
    }
    
    fun simulateFinalResults(text: String) = simulateFinalResult(text)  // Alias for consistency
    
    fun simulatePartialResults(text: String) = simulatePartialResult(text)  // Alias for consistency
    
    fun simulateError(errorCode: Int) {
        listener?.onError(errorCode)
        isListening = false
    }
    
    fun simulateEndOfSpeech() {
        listener?.onEndOfSpeech()
    }
}

/**
 * Factory that creates FakeSpeechRecognizer instances for testing.
 */
class FakeSpeechRecognizerFactory(
    private val recognizer: FakeSpeechRecognizer
) : SpeechRecognizerFactory {
    var available = true  // Can be set to false to test unavailable recognizer
    
    override fun createRecognizer(context: Context): SpeechRecognizerWrapper = recognizer
    override fun isRecognitionAvailable(context: Context): Boolean = available
}
