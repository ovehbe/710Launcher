package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {

    private val iconBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2, 3)

    private fun sample() = LauncherBackup(
        createdAtMillis = 1_793_000_000_000L,
        appVersionName = "1.6.0",
        appVersionCode = 19,
        sourcePackage = "com.meowgi.launcher710",
        prefs = mapOf(
            BackupCodec.SETTINGS_PREFS to mapOf(
                "accentColor" to 0xFF0073BC.toInt(),
                "headerVisible" to false,
                "someLong" to 9_007_199_254_740_000L,
                "someFloat" to 0.1f,
                "iconPackPackage" to "com.example.pack",
                "hiddenApps" to setOf("com.a/.Main", "com.b/.Main"),
                "emptySet" to emptySet<String>(),
                "pageApps_favorites" to setOf("com.fav/.Main"),
                "favoriteOrder" to """["com.fav/.Main","com.order/.Main"]""",
                "pageIntentShortcuts_favorites" to
                    """[{"name":"Macro","intentUri":"intent:#Intent;end","iconFile":"/data/old/cache/shortcut_icon_1.png"}]""",
                "unicode" to "Grüße — 你好 \"quoted\"\n"
            ),
            BackupCodec.DOCK_PREFS to mapOf(
                "dock_unified_order" to """["app:com.dock/.Main","shortcut:intent:#Intent;end"]""",
                "dock_apps" to "com.dock/.Main|com.dock2/.Main"
            )
        ),
        appStats = listOf(
            AppStatRecord("com.a/.Main", 12, true, 1_793_000_000_123L, frecency = 3.25, frecencyAt = 1_793_000_000_123L),
            AppStatRecord("com.b/.Main", 0, false, 0L)
        ),
        files = listOf(BackupFile("shortcut_icon_1.png", "/data/old/cache/shortcut_icon_1.png", iconBytes))
    )

    private fun assertRejected(json: String, messagePart: String) {
        try {
            BackupCodec.decode(json)
            fail("Expected rejection containing \"$messagePart\"")
        } catch (e: BackupException) {
            assertTrue("\"${e.message}\" should contain \"$messagePart\"", e.message!!.contains(messagePart, ignoreCase = true))
        }
    }

    private fun mutate(block: (JSONObject) -> Unit): String {
        val root = JSONObject(BackupCodec.encode(sample()))
        block(root)
        return root.toString()
    }

    @Test
    fun roundTripPreservesEverything() {
        val original = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun roundTripPreservesValueTypes() {
        val settings = BackupCodec.decode(BackupCodec.encode(sample())).prefs.getValue(BackupCodec.SETTINGS_PREFS)
        assertTrue(settings["accentColor"] is Int)
        assertTrue(settings["someLong"] is Long)
        assertTrue(settings["someFloat"] is Float)
        assertTrue(settings["headerVisible"] is Boolean)
        assertTrue(settings["hiddenApps"] is Set<*>)
        assertEquals(9_007_199_254_740_000L, settings["someLong"])
    }

    @Test
    fun encodeIsStable() {
        val once = BackupCodec.encode(sample())
        assertEquals(once, BackupCodec.encode(BackupCodec.decode(once)))
    }

    @Test
    fun emptyBackupRoundTrips() {
        val empty = LauncherBackup(
            createdAtMillis = 1, appVersionName = null, appVersionCode = 0, sourcePackage = null,
            prefs = mapOf(BackupCodec.SETTINGS_PREFS to emptyMap(), BackupCodec.DOCK_PREFS to emptyMap()),
            appStats = emptyList(), files = emptyList()
        )
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }

    @Test
    fun rejectsNonJsonAndTruncatedFiles() {
        assertRejected("", "empty")
        assertRejected("hello", "not valid JSON")
        val full = BackupCodec.encode(sample())
        assertRejected(full.substring(0, full.length / 2), "not valid JSON")
        assertRejected("[1,2,3]", "not valid JSON")
    }

    @Test
    fun rejectsForeignFiles() {
        assertRejected("""{"hello":"world"}""", "not a 710 Launcher backup")
        assertRejected(mutate { it.put("format", "com.other.launcher") }, "not a 710 Launcher backup")
    }

    @Test
    fun rejectsNewerSchema() {
        assertRejected(mutate { it.put("schemaVersion", BackupCodec.CURRENT_SCHEMA_VERSION + 1) }, "newer version")
        assertRejected(mutate { it.put("schemaVersion", Long.MAX_VALUE) }, "newer version")
    }

    @Test
    fun rejectsOlderUnsupportedSchema() {
        assertRejected(mutate { it.put("schemaVersion", 0) }, "unsupported old format")
        assertRejected("""{"entries":[],"version":0}""", "unsupported old format")
        assertRejected(mutate { it.put("schemaVersion", -3) }, "unsupported old format")
    }

    @Test
    fun rejectsMissingOrInvalidVersion() {
        assertRejected(mutate { it.remove("schemaVersion") }, "schemaVersion")
        assertRejected(mutate { it.put("schemaVersion", "2") }, "schemaVersion")
        assertRejected(mutate { it.put("schemaVersion", 2.5) }, "schemaVersion")
    }

    @Test
    fun rejectsCorruptPreferences() {
        assertRejected(mutate { it.remove("prefs") }, "no preferences")
        assertRejected(mutate { it.getJSONObject("prefs").put("evil_file", JSONObject()) }, "unknown preference file")
        assertRejected(mutate {
            it.getJSONObject("prefs").getJSONObject(BackupCodec.SETTINGS_PREFS)
                .put("accentColor", JSONObject().put("t", "i").put("v", "blue"))
        }, "accentColor")
        assertRejected(mutate {
            it.getJSONObject("prefs").getJSONObject(BackupCodec.SETTINGS_PREFS)
                .put("accentColor", JSONObject().put("t", "i").put("v", 1L shl 40))
        }, "accentColor")
        assertRejected(mutate {
            it.getJSONObject("prefs").getJSONObject(BackupCodec.SETTINGS_PREFS)
                .put("x", JSONObject().put("t", "blob").put("v", 1))
        }, "unknown type")
        assertRejected(mutate {
            it.getJSONObject("prefs").getJSONObject(BackupCodec.SETTINGS_PREFS)
                .put("hiddenApps", JSONObject().put("t", "set").put("v", JSONArray().put(1)))
        }, "hiddenApps")
        assertRejected(mutate {
            it.getJSONObject("prefs").getJSONObject(BackupCodec.SETTINGS_PREFS).put("headerVisible", "true")
        }, "headerVisible")
    }

    @Test
    fun rejectsCorruptDatabaseRows() {
        fun stats(root: JSONObject) = root.getJSONObject("database").getJSONArray("app_stats")
        assertRejected(mutate { it.put("database", JSONObject()) }, "app_stats")
        assertRejected(mutate { stats(it).getJSONObject(0).put("componentName", "") }, "invalid component")
        assertRejected(mutate { stats(it).getJSONObject(0).put("launchCount", -1) }, "launch count")
        assertRejected(mutate { stats(it).getJSONObject(0).put("isFavorite", "yes") }, "favorite")
        assertRejected(mutate { stats(it).put(JSONObject(stats(it).getJSONObject(0).toString())) }, "duplicate")
        assertRejected(mutate { stats(it).getJSONObject(0).put("frecency", -1) }, "usage score")
        assertRejected(mutate { stats(it).getJSONObject(0).put("frecency", "high") }, "usage score")
        assertRejected(mutate { stats(it).getJSONObject(0).put("frecencyAt", 1.5) }, "usage timestamp")
    }

    @Test
    fun usageScoreRoundTripsAndDefaultsWhenAbsent() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample())).appStats!!.first { it.componentName == "com.a/.Main" }
        assertEquals(3.25, decoded.frecency, 0.0)
        val withoutUsage = BackupCodec.decode(mutate { root ->
            val row = root.getJSONObject("database").getJSONArray("app_stats").getJSONObject(0)
            row.remove("frecency")
            row.remove("frecencyAt")
        }).appStats!!.first { it.componentName == "com.a/.Main" }
        assertEquals("seeded from launch count, like the DB migration", 12.0, withoutUsage.frecency, 0.0)
        assertEquals(1_793_000_000_123L, withoutUsage.frecencyAt)
    }

    @Test
    fun rejectsCorruptFiles() {
        fun file(root: JSONObject) = root.getJSONArray("files").getJSONObject(0)
        assertRejected(mutate { file(it).put("data", "!!!not base64!!!") }, "base64")
        assertRejected(mutate { file(it).put("sha256", "00") }, "checksum")
        assertRejected(mutate { file(it).put("name", "../../databases/evil.db") }, "invalid name")
        assertRejected(mutate { file(it).put("name", "..") }, "invalid name")
        assertRejected(mutate { it.getJSONArray("files").put(JSONObject(file(it).toString())) }, "duplicate file")
    }

    @Test
    fun noDatabaseSectionMeansLeaveDatabaseAlone() {
        val decoded = BackupCodec.decode(mutate { it.remove("database") })
        assertNull(decoded.appStats)
    }

    @Test
    fun migratesLegacySettingsExport() {
        val legacy = JSONObject().apply {
            put("version", 1)
            put("entries", JSONArray().apply {
                put(JSONObject().put("k", "accentColor").put("t", "i").put("v", 123))
                put(JSONObject().put("k", "headerVisible").put("t", "b").put("v", true))
                put(JSONObject().put("k", "iconPackPackage").put("t", "s").put("v", ""))
                put(JSONObject().put("k", "customPages").put("t", "s").put("v", "x"))
                put(JSONObject().put("k", "hiddenApps").put("t", "set").put("v", JSONArray().put("com.a/.Main")))
            })
        }
        val decoded = BackupCodec.decode(legacy.toString())
        assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(setOf(BackupCodec.SETTINGS_PREFS), decoded.prefs.keys)
        val settings = decoded.prefs.getValue(BackupCodec.SETTINGS_PREFS)
        assertEquals(123, settings["accentColor"])
        assertEquals(true, settings["headerVisible"])
        assertEquals("x", settings["customPages"])
        assertEquals(setOf("com.a/.Main"), settings["hiddenApps"])
        assertTrue("legacy \"\" meant unset", "iconPackPackage" !in settings)
        assertNull("legacy files had no database section", decoded.appStats)
    }

    @Test
    fun rejectsCorruptLegacyFile() {
        assertRejected("""{"version":1,"entries":[{"t":"i","v":1}]}""", "no key")
        assertRejected("""{"version":1,"entries":[{"k":"a","t":"i","v":"x"}]}""", "\"a\"")
    }

    @Test
    fun relocatesShortcutIconPaths() {
        val settings = sample().prefs.getValue(BackupCodec.SETTINGS_PREFS)
        assertEquals(setOf("/data/old/cache/shortcut_icon_1.png"), BackupCodec.referencedIconPaths(settings))
        val moved = BackupCodec.relocateIconPaths(settings, mapOf("/data/old/cache/shortcut_icon_1.png" to "/data/new/files/x.png"))
        assertEquals(setOf("/data/new/files/x.png"), BackupCodec.referencedIconPaths(moved))
        val arr = JSONArray(moved["pageIntentShortcuts_favorites"] as String)
        assertEquals("Macro", arr.getJSONObject(0).getString("name"))
        assertEquals(settings - "pageIntentShortcuts_favorites", moved - "pageIntentShortcuts_favorites")
    }

    @Test
    fun listsEveryReferencedPackage() {
        assertEquals(
            setOf("com.a", "com.b", "com.fav", "com.order", "com.dock", "com.dock2"),
            BackupCodec.referencedPackages(sample())
        )
    }
}
