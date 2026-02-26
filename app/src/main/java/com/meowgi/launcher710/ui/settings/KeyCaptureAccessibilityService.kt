package com.meowgi.launcher710.ui.settings

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice

/**
 * Accessibility service used only when recording key shortcuts in Settings
 * and for injecting the first key in "Launch app/shortcut and inject key" search mode.
 * When recording, consumes key events (like KeyMapper) so Home/Back etc. do not trigger
 * their system action. Only active when recording dialog is open (our app).
 */
class KeyCaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        setInstance(this)
        val info = serviceInfo ?: return
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    override fun onDestroy() {
        setInstance(null)
        super.onDestroy()
    }

    /**
     * Injects a key event into the system (e.g. into the app that was just launched).
     * Requires API 24+. Uses reflection to call getUiAutomation() and injectInputEvent.
     * Call from launcher after delay when using "Launch and inject key" search mode.
     */
    fun injectKey(keyCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val getUiAutomation = AccessibilityService::class.java.getMethod("getUiAutomation")
            val uiAutomation = getUiAutomation.invoke(this) ?: return
            val now = SystemClock.uptimeMillis()
            val source = InputDevice.SOURCE_KEYBOARD
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0, 0, 0, source)
            val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0, 0, 0, 0, 0, source)
            val injectMethod = uiAutomation.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Boolean::class.javaPrimitiveType)
            injectMethod.invoke(uiAutomation, down, true)
            injectMethod.invoke(uiAutomation, up, true)
        } catch (_: Exception) { }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!KeyCaptureReceiver.recordingMode) return false
        // When recording, consume the key so it doesn't trigger Home/Back/etc. (KeyMapper-style).
        // Only our app shows the record dialog, so we don't check rootInActiveWindow — that check
        // can fail on some devices (null or wrong window when key is delivered).
        val keyCode = event.keyCode
        KeyCaptureReceiver.notifyKey(keyCode)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
        val key = pendingInjectKeyCode ?: return
        var source: AccessibilityNodeInfo? = null
        try {
            source = event.source ?: return
            if (source.packageName == packageName) return
            if (!source.isEditable && !source.isFocusable) return
            pendingInjectKeyCode = null
            injectKey(key)
        } finally {
            source?.recycle()
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: KeyCaptureAccessibilityService? = null
            private set

        @Volatile
        var pendingInjectKeyCode: Int? = null

        fun setInstance(service: KeyCaptureAccessibilityService?) {
            instance = service
        }

        fun setPendingKeyInject(keyCode: Int) {
            pendingInjectKeyCode = keyCode
        }

        fun clearPendingKeyInject() {
            pendingInjectKeyCode = null
        }
    }

    object KeyCaptureReceiver {
        @Volatile
        var recordingMode = false
            private set

        private var listener: ((Int) -> Unit)? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun setRecording(recording: Boolean, onKey: ((Int) -> Unit)?) {
            recordingMode = recording
            listener = onKey
        }

        fun notifyKey(keyCode: Int) {
            val l = listener
            if (l != null) {
                mainHandler.post { l(keyCode) }
            }
        }
    }
}
