package com.meowgi.launcher710.ui.settings

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used only when recording key shortcuts in Settings.
 * Only captures keys when our app (launcher or settings) is in the foreground,
 * so it does not affect keymaps in other apps.
 *
 * TODO: Key capturing still doesn't work like KeyMapper in some cases; to be improved later.
 */
class KeyCaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: return
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!KeyCaptureReceiver.recordingMode) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName != packageName) return false
        val keyCode = event.keyCode
        KeyCaptureReceiver.notifyKey(keyCode)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

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
