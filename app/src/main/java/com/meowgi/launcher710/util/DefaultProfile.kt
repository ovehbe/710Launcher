package com.meowgi.launcher710.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and applies the factory default profile from assets/default_profile.json on fresh installs.
 * Uses the same typed-pref format as the backup system ({t,v} JSON objects).
 */
object DefaultProfile {

    private const val ASSET_PATH = "default_profile.json"
    private const val APPLIED_FLAG = "defaultProfileApplied"
    private const val SETTINGS_PREFS = "meowgi_launcher710_settings"
    private const val DOCK_PREFS = "meowgi_launcher710_dock"

    /**
     * Applies the default profile if this is a fresh install (no existing prefs and the flag is not set).
     * Must be called before the first render. Returns true if defaults were applied.
     */
    fun applyIfFresh(context: Context): Boolean {
        val settingsPrefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (settingsPrefs.contains(APPLIED_FLAG)) return false
        if (settingsPrefs.all.isNotEmpty()) {
            settingsPrefs.edit().putBoolean(APPLIED_FLAG, true).apply()
            return false
        }
        
        return try {
            val defaults = load(context)
            apply(context, defaults)
            settingsPrefs.edit().putBoolean(APPLIED_FLAG, true).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Loads the default profile, applies it (overwriting current settings), and marks it as applied.
     * Used by the "Reset to default style" action.
     */
    fun reset(context: Context) {
        val defaults = load(context)
        apply(context, defaults)
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(APPLIED_FLAG, true).apply()
    }

    private data class ProfileData(
        val prefs: Map<String, Map<String, Any>>,
        val dock: Map<String, Any>
    )

    private fun load(context: Context): ProfileData {
        val json = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val prefs = root.optJSONObject("prefs")?.let { parsePrefsFile(it) } ?: emptyMap()
        val dock = root.optJSONObject("dock")?.let { parsePrefsFile(it) } ?: emptyMap()
        return ProfileData(mapOf(SETTINGS_PREFS to prefs), dock)
    }

    private fun parsePrefsFile(obj: JSONObject): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (key in obj.keys()) {
            val entry = obj.optJSONObject(key) ?: continue
            val value = decodePref(entry) ?: continue
            result[key] = value
        }
        return result
    }

    private fun decodePref(entry: JSONObject): Any? {
        val type = entry.optString("t")
        val v = entry.opt("v")
        return when (type) {
            "s" -> v as? String
            "i" -> (v as? Number)?.toInt()
            "l" -> (v as? Number)?.toLong()
            "f" -> (v as? Number)?.toFloat()
            "b" -> v as? Boolean
            "set" -> {
                val arr = v as? JSONArray ?: return null
                (0 until arr.length()).mapNotNullTo(LinkedHashSet()) { arr.optString(it) }
            }
            else -> null
        }
    }

    private fun apply(context: Context, data: ProfileData) {
        val pm = context.packageManager
        for ((prefsName, values) in data.prefs) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val filtered = filterMissingPackages(values, pm)
            prefs.edit().clear().also { editor ->
                for ((key, value) in filtered) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    }
                }
            }.apply()
        }
        
        val dockPrefs = context.getSharedPreferences(DOCK_PREFS, Context.MODE_PRIVATE)
        val filteredDock = filterMissingPackages(data.dock, pm)
        dockPrefs.edit().clear().also { editor ->
            for ((key, value) in filteredDock) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
        }.apply()
    }

    /**
     * Filters out references to packages that aren't installed, gracefully falling back to defaults.
     * Handles: icon packs, dock apps, favorites, hidden apps, action packages, custom icons/labels.
     */
    private fun filterMissingPackages(values: Map<String, Any>, pm: PackageManager): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, value) in values) {
            val filtered = when {
                key.endsWith("IconPackPackage") && value is String -> {
                    if (value.isEmpty() || isPackageInstalled(value, pm)) value else ""
                }
                key.endsWith("ActionPackage") && value is String -> {
                    if (value.isEmpty() || isPackageInstalled(value, pm)) value else null
                }
                key == "dock_apps" && value is String -> {
                    filterComponentList(value, "|", pm)
                }
                key == "dock_unified_order" && value is String -> {
                    filterJsonArray(value, pm)
                }
                key == "favoriteOrder" && value is String -> {
                    filterJsonArray(value, pm)
                }
                key == "notificationApplets" && value is String -> {
                    filterJsonArray(value, pm)
                }
                key == "hiddenApps" && value is Set<*> -> {
                    (value as Set<String>).filterTo(LinkedHashSet()) { isPackageOrComponentInstalled(it, pm) }
                }
                key.startsWith("pageApps_") && value is Set<*> -> {
                    (value as Set<String>).filterTo(LinkedHashSet()) { isPackageOrComponentInstalled(it, pm) }
                }
                key.startsWith("customIcons") && value is String -> {
                    filterJsonMap(value, checkKeys = true, pm)
                }
                key.startsWith("customLabels") && value is String -> {
                    filterJsonMap(value, checkKeys = true, pm)
                }
                key.startsWith("appletCustom") && value is String -> {
                    filterJsonMap(value, checkKeys = false, pm)
                }
                key.startsWith("dockSwipeAction_") && value is String -> {
                    if (value.isEmpty() || isPackageOrComponentInstalled(value, pm)) value else null
                }
                else -> value
            }
            if (filtered != null) {
                result[key] = filtered
            }
        }
        return result
    }

    private fun isPackageInstalled(pkg: String, pm: PackageManager): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isPackageOrComponentInstalled(spec: String, pm: PackageManager): Boolean {
        val pkg = spec.substringBefore('/')
        return isPackageInstalled(pkg, pm)
    }

    private fun filterComponentList(value: String, separator: String, pm: PackageManager): String {
        return value.split(separator)
            .filter { it.isEmpty() || isPackageOrComponentInstalled(it, pm) }
            .joinToString(separator)
    }

    private fun filterJsonArray(jsonStr: String, pm: PackageManager): String {
        return try {
            val arr = JSONArray(jsonStr)
            val filtered = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i)
                val component = item.removePrefix("app:")
                if (component.isEmpty() || isPackageOrComponentInstalled(component, pm)) {
                    filtered.put(item)
                }
            }
            filtered.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    private fun filterJsonMap(jsonStr: String, checkKeys: Boolean, pm: PackageManager): String {
        return try {
            val obj = JSONObject(jsonStr)
            val filtered = JSONObject()
            for (key in obj.keys()) {
                if (!checkKeys || isPackageOrComponentInstalled(key, pm)) {
                    filtered.put(key, obj.get(key))
                }
            }
            filtered.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * Returns all preference keys referenced in the default profile, for validation in tests.
     */
    fun getProfileKeys(context: Context): Set<String> {
        return try {
            val data = load(context)
            data.prefs.values.flatMapTo(LinkedHashSet()) { it.keys } + data.dock.keys
        } catch (e: Exception) {
            emptySet()
        }
    }
}
