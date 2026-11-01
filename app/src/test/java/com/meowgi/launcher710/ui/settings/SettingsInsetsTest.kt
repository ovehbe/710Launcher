package com.meowgi.launcher710.ui.settings

import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.meowgi.launcher710.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsInsetsTest {

    @Test
    fun everyScreenIsPaddedClearOfTheSystemBars() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val flags = activity.window.attributes.flags
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

        val root = activity.findViewById<View>(R.id.settingsRoot)
        val toolbar = activity.findViewById<View>(R.id.settingsToolbar)
        val baseToolbarTop = toolbar.paddingTop
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 63, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .build()

        for (screen in SettingsScreen.values()) {
            activity.showScreen(screen)
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            assertEquals(screen.name, baseToolbarTop + 63, toolbar.paddingTop)
            assertEquals(screen.name, 48, root.paddingBottom)
            assertEquals(screen.name, 0, root.paddingTop)
        }

        ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertTrue("re-dispatch must not accumulate", toolbar.paddingTop == baseToolbarTop + 63)
    }
}
