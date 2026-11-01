package com.meowgi.launcher710.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Settings lives in its own task so it shows in Recents and survives Home / app switches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTaskTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun info(cls: Class<*>): ActivityInfo =
        context.packageManager.getActivityInfo(ComponentName(context, cls), 0)

    @Test
    fun settingsHasItsOwnRecentsTask() {
        val settings = info(SettingsActivity::class.java)
        val launcher = info(LauncherActivity::class.java)
        assertEquals("${context.packageName}.settings", settings.taskAffinity)
        assertNotEquals(launcher.taskAffinity, settings.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, settings.launchMode)
        for ((flag, name) in listOf(
            ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS to "excludeFromRecents",
            ActivityInfo.FLAG_NO_HISTORY to "noHistory",
            ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH to "finishOnTaskLaunch",
            ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS to "autoRemoveFromRecents",
            ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH to "clearTaskOnLaunch"
        )) assertEquals(name, 0, settings.flags and flag)
        assertEquals(context.getString(R.string.launcher_settings_label), context.getString(settings.labelRes))
    }

    @Test
    fun everyStartGoesThroughTheNewTaskIntent() {
        assertTrue(SettingsActivity.intent(context).flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // Any other `Intent(…, SettingsActivity::class.java)` would land in the launcher's home task.
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SettingsActivity.kt" }
            .filter { it.readText().contains("SettingsActivity::class") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun backFromASubScreenReturnsToTheCategoryListThenFinishes() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, SettingsActivity.intent(context)).setup().get()
        val title = activity.findViewById<TextView>(R.id.settingsTitle)
        activity.showScreen(SettingsScreen.SEARCH)
        assertEquals("Search", title.text.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(context.getString(R.string.settings_title), title.text.toString())
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun leavingAndRecreatingKeepsTheScreen() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, SettingsActivity.intent(context)).setup()
        controller.get().showScreen(SettingsScreen.NOTIFICATIONS)
        // Home / app switch: paused and stopped, never finished.
        controller.pause().stop()
        assertFalse(controller.get().isFinishing)
        controller.recreate()
        val title = controller.get().findViewById<TextView>(R.id.settingsTitle)
        assertEquals("Notifications", title.text.toString())
    }

    @Test
    fun restoreReloadRequestIsConsumedByTheLauncher() {
        LauncherActivity.reloadRequested = true
        Robolectric.buildActivity(LauncherActivity::class.java).setup()
        assertFalse(LauncherActivity.reloadRequested)
    }
}
