package com.meowgi.launcher710.util

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.model.AppInfo
import com.meowgi.launcher710.ui.search.SearchOverlay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchGuardsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun launchingAnAppThatIsGoneDoesNotCrash() {
        shadowOf(context as android.app.Application).checkActivities(true)
        val gone = AppInfo(
            label = "Gone", packageName = "com.example.gone", activityName = "com.example.gone.Main",
            icon = ColorDrawable(0)
        )
        assertEquals(ComponentName("com.example.gone", "com.example.gone.Main"), gone.componentName)
        AppRepository.get(context).launchApp(gone) // used to throw ActivityNotFoundException
    }

    @Test
    fun webSearchWithoutAnyHandlerTellsTheUserInsteadOfCrashing() {
        shadowOf(context as android.app.Application).checkActivities(true)
        SearchOverlay(context).openWebSearchWithQuery("bb7") // used to throw from the fallback
        assertEquals("No app found to open the search", ShadowToast.getTextOfLatestToast())
    }
}
