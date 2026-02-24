package com.meowgi.launcher710.ui.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListenerService : NotificationListenerService() {

    companion object {
        var instance: NotifListenerService? = null
            private set
        var onNotificationsChanged: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        onNotificationsChanged?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        onNotificationsChanged?.invoke()
    }

    fun getNotifications(): List<StatusBarNotification> {
        return try {
            activeNotifications?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
