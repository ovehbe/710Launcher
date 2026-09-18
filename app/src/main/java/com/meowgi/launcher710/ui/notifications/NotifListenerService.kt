package com.meowgi.launcher710.ui.notifications

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListenerService : NotificationListenerService() {

    companion object {
        private const val DEBOUNCE_MS = 120L

        var instance: NotifListenerService? = null
            private set
        var onNotificationsChanged: (() -> Unit)? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val dispatchChange = Runnable { onNotificationsChanged?.invoke() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainHandler.removeCallbacks(dispatchChange)
    }

    /**
     * Coalesces bursts of notification changes into a single callback. A busy app can post a dozen
     * updates in a row, and each one used to fan out into four separate UI rebuilds.
     */
    private fun notifyChange() {
        mainHandler.removeCallbacks(dispatchChange)
        mainHandler.postDelayed(dispatchChange, DEBOUNCE_MS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        notifyChange()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notifyChange()
    }

    fun getNotifications(): List<StatusBarNotification> {
        return try {
            activeNotifications?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Dismiss a single notification by its key. */
    fun dismissNotification(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Exception) { }
    }

    /** Dismiss all notifications. Uses system clear-all when available; otherwise cancels each by key. */
    fun dismissAllNotifications() {
        try {
            cancelAllNotifications()
        } catch (_: Exception) {
            try {
                val keys = activeNotifications?.map { it.key } ?: return
                keys.forEach { cancelNotification(it) }
            } catch (_: Exception) { }
        }
    }
}
