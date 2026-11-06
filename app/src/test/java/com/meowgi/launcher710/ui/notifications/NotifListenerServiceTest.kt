package com.meowgi.launcher710.ui.notifications

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifListenerServiceTest {

    @After
    fun tearDown() {
        NotifListenerService.onNotificationsChanged = null
    }

    @Test
    fun connectingTheListenerRefreshesTheUi() {
        // After a rebind the service exists before the system lets it read notifications, so the
        // hub and ticker showed nothing until the next post or removal.
        var refreshes = 0
        NotifListenerService.onNotificationsChanged = { refreshes++ }
        val service = Robolectric.buildService(NotifListenerService::class.java).create().get()

        service.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, refreshes)
    }
}
