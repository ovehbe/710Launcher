package com.meowgi.launcher710.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the settings restructure against lost settings. `settings_pref_baseline.txt` is a snapshot
 * of every LauncherPrefs symbol and entry point the old flat settings screen used; each must still be
 * reachable from the new screens and keep its SharedPreferences key.
 */
class SettingsPrefCoverageTest {

    private val settingsDir = File("src/main/java/com/meowgi/launcher710/ui/settings")
    private val prefsFile = File("src/main/java/com/meowgi/launcher710/util/LauncherPrefs.kt")

    /** Files that decide what is on screen: screen builders, row widgets and tap-action rows. */
    private val screenLayer = listOf("SettingsScreens.kt", "SettingsRows.kt", "SettingsTapActions.kt")

    /** Old entry points that were consolidated, mapped to what replaced them on screen. */
    private val renamedActions = mapOf(
        "showHeaderDateActionPicker" to listOf("showTapActionPicker", "TapAction.HEADER_DATE"),
        "showHeaderClockActionPicker" to listOf("showTapActionPicker", "TapAction.HEADER_CLOCK"),
        "showActionBarCenterActionPicker" to listOf("showTapActionPicker", "TapAction.ACTION_BAR_TAP"),
        "showActionBarCenterLongPressActionPicker" to listOf("showTapActionPicker", "TapAction.ACTION_BAR_LONG_PRESS"),
        "addInjectDelayInput" to listOf("numberRow", "searchEngineLaunchInjectDelayMs"),
        "addInjectAlternativeWindowInput" to listOf("numberRow", "searchEngineLaunchInjectAlternativeWindowMs"),
        "addKeyShortcutsInfo" to listOf("noteRow(KEY_SHORTCUTS_HELP)"),
        "ACTION_CREATE_SHORTCUT" to listOf("launchShortcutPicker"),
        "exportLauncher" to listOf("backupLauncher"),
        "importLauncher" to listOf("restoreLauncher")
    )

    /** Dialog-layer prefs calls superseded by the full backup, which still reads the old JSON exports. */
    private val replacedDialogSymbols = mapOf(
        "exportToJson" to "BackupManager",
        "importFromJson" to "BackupManager"
    )

    private class Baseline(val rows: Map<String, String>, val dialogs: Map<String, String>, val actions: List<String>)

    private val baseline: Baseline by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("settings_pref_baseline.txt")!!
            .bufferedReader().readText()
        val sections = mutableMapOf<String, MutableList<String>>()
        var current = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) { current = line.trim('[', ']'); continue }
            sections.getOrPut(current) { mutableListOf() }.add(line)
        }
        fun pairs(name: String) = sections.getValue(name).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        Baseline(pairs("rows"), pairs("dialogs"), sections.getValue("actions"))
    }

    private fun source(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    private val screenSource by lazy { screenLayer.joinToString("\n") { source(File(settingsDir, it)) } }
    private val allSettingsSource by lazy {
        settingsDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }.joinToString("\n") { source(it) }
    }

    private fun references(src: String, symbol: String) = Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(src)

    @Test
    fun baselineIsComplete() {
        assertTrue("rows: ${baseline.rows.size}", baseline.rows.size >= 90)
        assertTrue("dialogs: ${baseline.dialogs.size}", baseline.dialogs.size >= 30)
        assertTrue("actions: ${baseline.actions.size}", baseline.actions.size >= 30)
    }

    @Test
    fun everyRowSettingIsOnAScreen() {
        val missing = baseline.rows.keys.filterNot { references(screenSource, it) }
        assertTrue("Settings rows no longer reachable from any screen: $missing", missing.isEmpty())
    }

    @Test
    fun everyDialogSettingIsStillUsed() {
        val missing = baseline.dialogs.keys.filterNot { references(allSettingsSource, replacedDialogSymbols[it] ?: it) }
        assertTrue("Prefs used by settings dialogs but no longer referenced: $missing", missing.isEmpty())
    }

    @Test
    fun everyEntryPointIsStillWired() {
        val missing = baseline.actions.filterNot { action ->
            val needles = renamedActions[action] ?: listOf(action)
            needles.all { needle ->
                if (needle.all { it.isLetterOrDigit() || it == '_' }) references(screenSource, needle) else screenSource.contains(needle)
            }
        }
        assertTrue("Settings entry points no longer reachable from any screen: $missing", missing.isEmpty())
    }

    @Test
    fun prefKeysAreUnchanged() {
        val prefs = prefsFile.readText()
        val changed = (baseline.rows + baseline.dialogs).filterValues { it.isNotEmpty() }.filterNot { (symbol, key) ->
            val start = Regex("\\n    var ${Regex.escape(symbol)}:").find(prefs) ?: return@filterNot false
            val rest = prefs.substring(start.range.last)
            val end = Regex("\\n    (var|val|fun|private|internal|companion|/\\*\\*) ").find(rest)?.range?.first ?: rest.length
            val block = rest.substring(0, end)
            val setterAt = block.indexOf("set(")
            setterAt > 0 && block.substring(0, setterAt).contains("\"$key\"") && block.substring(setterAt).contains("\"$key\"")
        }
        assertTrue("Pref properties whose getter or setter no longer uses the original key: $changed", changed.isEmpty())
    }

    @Test
    fun everyCategoryIsReachableFromHome() {
        val screens = source(File(settingsDir, "SettingsScreens.kt"))
        val home = screens.substringAfter("fun SettingsActivity.buildHome()").substringBefore("\nprivate ")
        val unreachable = SettingsScreen.values().filter { it != SettingsScreen.HOME && !home.contains("SettingsScreen.${it.name}") }
        assertTrue("Categories missing from the settings home list: $unreachable", unreachable.isEmpty())
    }
}
