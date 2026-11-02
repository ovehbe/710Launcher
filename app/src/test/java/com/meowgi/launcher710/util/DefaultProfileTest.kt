package com.meowgi.launcher710.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultProfileTest {

    private lateinit var context: Context
    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var dockPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsPrefs = context.getSharedPreferences("meowgi_launcher710_settings", Context.MODE_PRIVATE)
        dockPrefs = context.getSharedPreferences("meowgi_launcher710_dock", Context.MODE_PRIVATE)
        settingsPrefs.edit().clear().apply()
        dockPrefs.edit().clear().apply()
    }

    @Test
    fun freshInstallAppliesDefaults() {
        assertTrue("Fresh install should apply defaults", DefaultProfile.applyIfFresh(context))
        assertTrue("Flag should be set", settingsPrefs.contains("defaultProfileApplied"))
        assertTrue("Settings should be populated", settingsPrefs.all.size > 10)
        assertTrue("Dock should be populated", dockPrefs.all.isNotEmpty())
    }

    @Test
    fun existingInstallSkipsDefaults() {
        settingsPrefs.edit().putString("someExistingKey", "value").apply()
        assertFalse("Existing install should skip defaults", DefaultProfile.applyIfFresh(context))
        assertTrue("Flag should still be set", settingsPrefs.contains("defaultProfileApplied"))
        assertFalse("Existing prefs should not be overwritten", settingsPrefs.contains("accentColor"))
    }

    @Test
    fun secondCallSkipsDefaults() {
        assertTrue("First call should apply", DefaultProfile.applyIfFresh(context))
        val firstSize = settingsPrefs.all.size
        settingsPrefs.edit().putString("customKey", "customValue").apply()
        
        assertFalse("Second call should skip", DefaultProfile.applyIfFresh(context))
        assertTrue("Custom pref should remain", settingsPrefs.contains("customKey"))
        assertTrue("Settings size should be different", settingsPrefs.all.size > firstSize)
    }

    @Test
    fun missingPackagesTolerated() {
        DefaultProfile.applyIfFresh(context)
        val iconPack = settingsPrefs.getString("iconPackPackage", null)
        assertNotNull("Icon pack pref should exist even if package is missing", iconPack)
        
        val dockApps = dockPrefs.getString("dock_apps", null)
        assertNotNull("Dock apps should be present", dockApps)
    }

    @Test
    fun allProfileKeysAreValid() {
        val profileKeys = DefaultProfile.getProfileKeys(context)
        assertTrue("Profile should have keys", profileKeys.isNotEmpty())
        assertTrue("Profile should have substantial content", profileKeys.size > 20)
        
        DefaultProfile.applyIfFresh(context)
        
        assertTrue("Some settings keys should be applied", settingsPrefs.all.size > 20)
        assertTrue("Profile keys should all be recognized types", 
            profileKeys.all { it.isNotEmpty() && it.length < 200 })
    }

    @Test
    fun resetOverwritesExistingSettings() {
        settingsPrefs.edit().putString("customKey", "customValue").apply()
        DefaultProfile.reset(context)
        
        assertTrue("Flag should be set", settingsPrefs.contains("defaultProfileApplied"))
        assertFalse("Custom key should be gone", settingsPrefs.contains("customKey"))
        assertTrue("Defaults should be present", settingsPrefs.contains("accentColor"))
    }

    @Test
    fun profileHasExpectedStructure() {
        DefaultProfile.applyIfFresh(context)
        
        val accentColor = settingsPrefs.getInt("accentColor", 0)
        assertNotEquals("Accent color should be set", 0, accentColor)
        
        val aiEnabled = settingsPrefs.getBoolean("aiEnabled", false)
        assertTrue("AI should be enabled", aiEnabled)
        
        val aiBaseUrl = settingsPrefs.getString("aiBaseUrl", null)
        assertNotNull("AI base URL should be set", aiBaseUrl)
        assertTrue("AI base URL should be Groq", aiBaseUrl?.contains("groq.com") == true)
        
        val gridColumns = settingsPrefs.getInt("gridColumns", 0)
        assertEquals("Grid columns should be 6", 6, gridColumns)
        
        val iconPackPackage = settingsPrefs.getString("iconPackPackage", null)
        assertNotNull("Icon pack should be set", iconPackPackage)
    }

    @Test
    fun dockSettingsAreApplied() {
        DefaultProfile.applyIfFresh(context)
        
        val dockApps = dockPrefs.getString("dock_apps", "") ?: ""
        val dockOrder = dockPrefs.getString("dock_unified_order", "") ?: ""
        
        assertTrue("Dock should have some content", dockApps.isNotEmpty() || dockOrder.isNotEmpty())
        assertTrue("Dock prefs should have at least one entry", dockPrefs.all.isNotEmpty())
    }
}
