package com.meowgi.launcher710.ui.notifications

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListenerService : NotificationListenerService() {

    companion object {
        var instance: NotifListenerService? = null
            private set
        var onNotificationsChanged: (() -> Unit)? = null
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun notifyChange() {
        val cb = onNotificationsChanged ?: return
        mainHandler.post { cb() }
        mainHandler.postDelayed({ cb() }, 400)
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
