package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackupScanTest {

    private val root = "/data/user/0/com.meowgi.launcher710"

    @Test
    fun pathsAreFoundInsideJsonWithEscapedSlashes() {
        val json = JSONArray().put(JSONObject().put("name", "X").put("iconFile", "$root/cache/a.png")).toString()
        val prefs = mapOf("s" to mapOf<String, Any>(
            "pageIntentShortcuts_p" to json.replace("/", "\\/"),
            "plain" to "$root/files/b.png",
            "set" to setOf("$root/files/c.png", "nope"),
            "outside" to "/sdcard/d.png",
            "escape" to "$root/../other/e.png",
            "n" to 3
        ))
        assertEquals(
            setOf("$root/cache/a.png", "$root/files/b.png", "$root/files/c.png"),
            BackupScan.referencedPaths(prefs, listOf(root))
        )
    }

    @Test
    fun relocateRewritesStringsSetsAndJsonLeavesOnly() {
        val json = JSONArray().put(JSONObject().put("iconFile", "$root/cache/a.png").put("name", "A")).toString()
        val untouched = """{"iconFile":"$root/cache/other.png"}"""
        val values = mapOf<String, Any>("j" to json, "s" to "$root/cache/a.png", "set" to setOf("$root/cache/a.png", "k"), "u" to untouched, "i" to 1)
        val out = BackupScan.relocate(values, mapOf("$root/cache/a.png" to "$root/files/new.png"))
        assertEquals("$root/files/new.png", JSONArray(out["j"] as String).getJSONObject(0).getString("iconFile"))
        assertEquals("A", JSONArray(out["j"] as String).getJSONObject(0).getString("name"))
        assertEquals("$root/files/new.png", out["s"])
        assertEquals(setOf("$root/files/new.png", "k"), out["set"])
        assertSame(untouched, out["u"])
        assertEquals(1, out["i"])
    }

    @Test
    fun referencedPackagesCoverComponentsPipesPackageKeysAndDbCells() {
        val payload = BackupPayload(
            createdAtMillis = 0,
            source = BackupSource(null, null, 0),
            prefs = mapOf("s" to mapOf<String, Any>(
                "dock_apps" to "com.dock.one/.Main,com.dock.two/com.dock.two.Act",
                "shortcuts" to JSONArray().put("com.pinned.app|shortcut-7").toString(),
                "intent" to "intent:#Intent;component=com.intent.app/.Go;end",
                "iconPackPackage" to "com.icons.pack",
                "searchUrl" to "https://www.google.com/search?q=%s",
                "version" to "1.5.5",
                "own" to "com.meowgi.launcher710/.LauncherActivity"
            )),
            tables = mapOf("app_stats" to TableData(listOf("componentName", "n"), listOf(listOf("com.db.app/com.db.app.Main", 1L)))),
            assets = emptyList()
        )
        assertEquals(
            setOf("com.dock.one", "com.dock.two", "com.pinned.app", "com.intent.app", "com.icons.pack", "com.db.app"),
            BackupScan.referencedPackages(payload, "com.meowgi.launcher710")
        )
    }
}
