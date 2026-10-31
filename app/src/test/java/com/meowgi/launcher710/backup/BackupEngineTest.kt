package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BackupEngineTest {

    private val pkg = "com.meowgi.launcher710"
    private val root = "/data/user/0/$pkg"
    private val iconPath = "$root/cache/shortcut_icon_1.png"
    private val iconBytes = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private val statsColumns = listOf("componentName", "launchCount", "isFavorite", "lastLaunched", "frecency", "frecencyAt")

    // ------------------------------------------------------------------ fakes

    class FakePrefs(val files: MutableMap<String, Map<String, Any>> = linkedMapOf()) : PrefsStore {
        /** Throws on the n-th write (1-based). */
        var failOnWrite = 0
        var writes = 0
        override fun fileNames() = files.keys.toList()
        override fun read(name: String) = files[name] ?: emptyMap()
        override fun write(name: String, values: Map<String, Any>) {
            writes++
            if (writes == failOnWrite) throw IOException("disk full")
            files[name] = LinkedHashMap(values)
        }
    }

    class FakeDb(val schema: Map<String, List<String>>, val tables: MutableMap<String, TableData> = linkedMapOf()) : DbStore {
        var failOnReplace: String? = null
        init {
            for ((t, cols) in schema) tables.putIfAbsent(t, TableData(cols, emptyList()))
        }
        override fun tableNames() = schema.keys.toList()
        override fun columns(table: String) = schema.getValue(table)
        override fun read(table: String) = tables.getValue(table)
        override fun replaceAll(table: String, data: TableData) {
            if (table == failOnReplace) throw IOException("db locked")
            val cols = schema.getValue(table)
            require(data.columns.all { it in cols }) { "unknown column in $table" }
            val rows = data.rows.map { row -> cols.map { c -> data.columns.indexOf(c).let { if (it < 0) 0L else row[it] } } }
            tables[table] = TableData(cols, rows)
        }
    }

    class FakeAssets(private val root: String, val files: MutableMap<String, ByteArray> = linkedMapOf()) : AssetStore {
        val staged = linkedMapOf<String, Map<String, ByteArray>>()
        var failOnCommit = false
        override fun read(path: String) = files[path]
        override fun stage(id: String, assets: List<BackupAsset>): Map<String, String> {
            staged[id] = assets.associate { it.name to it.bytes }
            return assets.associate { it.name to "$root/files/backup_assets/$id/${it.name}" }
        }
        override fun commit(id: String) {
            if (failOnCommit) throw IOException("rename failed")
            staged.remove(id)?.forEach { (name, bytes) -> files["$root/files/backup_assets/$id/$name"] = bytes }
        }
        override fun discard(id: String) {
            staged.remove(id)
            files.keys.removeAll { it.startsWith("$root/files/backup_assets/$id/") }
        }
    }

    class FakeJournal : Journal {
        var entry: Pair<String, ByteArray>? = null
        override fun begin(id: String, snapshot: ByteArray) { entry = id to snapshot }
        override fun pending() = entry
        override fun finish() { entry = null }
    }

    private inner class Device(
        prefs: MutableMap<String, Map<String, Any>> = linkedMapOf(),
        tables: MutableMap<String, TableData> = linkedMapOf(),
        files: MutableMap<String, ByteArray> = linkedMapOf(),
        schema: Map<String, List<String>> = mapOf("app_stats" to statsColumns)
    ) {
        val prefs = FakePrefs(prefs)
        val db = FakeDb(schema, tables)
        val assets = FakeAssets(root, files)
        val journal = FakeJournal()
        private var now = 1_000L
        fun engine() = BackupEngine(prefs, db, assets, journal, listOf(root, "/data/data/$pkg"), BackupSource(pkg, "1.5.5", 18)) { now++ }

        fun state() = Triple(prefs.files.toMap(), db.tables.toMap(), assets.files.mapValues { it.value.toList() })
    }

    private fun shortcutsJson(icon: String) =
        JSONArray().put(JSONObject().put("name", "Maps").put("intentUri", "intent:#Intent;component=com.maps/.Main;end").put("iconFile", icon)).toString()

    private fun sourceDevice() = Device(
        prefs = linkedMapOf(
            "meowgi_launcher710_settings" to mapOf(
                "accentColor" to 7,
                "headerVisible" to false,
                "fontScale" to 1.1f,
                "installTime" to 123L,
                "listViewPages" to setOf("all"),
                "pageIntentShortcuts_p1" to shortcutsJson(iconPath)
            ),
            "dock" to mapOf("dock_unified_order" to "app:com.a/.Main")
        ),
        tables = linkedMapOf(
            "app_stats" to TableData(statsColumns, listOf(listOf("com.a/com.a.Main", 4L, 1L, 99L, 3.5, 99L)))
        ),
        files = linkedMapOf(iconPath to iconBytes, "$root/cache/unreferenced.png" to byteArrayOf(9))
    )

    private fun targetDevice() = Device(
        prefs = linkedMapOf(
            "meowgi_launcher710_settings" to mapOf("accentColor" to 1, "onlyHere" to "x"),
            "dock" to mapOf("dock_unified_order" to "app:com.old/.Main"),
            "unrelated" to mapOf("keep" to true)
        ),
        tables = linkedMapOf(
            "app_stats" to TableData(statsColumns, listOf(listOf("com.old/com.old.Main", 1L, 0L, 5L, 1.0, 5L)))
        )
    )

    private fun backupOf(d: Device) = BackupCodec.decode(BackupCodec.encode(d.engine().capture()))

    // ------------------------------------------------------------------ tests

    @Test
    fun captureCoversEveryPrefsFileTableAndReferencedFile() {
        val p = sourceDevice().engine().capture()
        assertEquals(setOf("meowgi_launcher710_settings", "dock"), p.prefs.keys)
        assertEquals(setOf("app_stats"), p.tables.keys)
        assertEquals(listOf(iconPath), p.assets.map { it.originalPath })
        assertArrayEquals(iconBytes, p.assets.single().bytes)
    }

    @Test
    fun roundTripRestoresEverythingAndRelocatesIcons() {
        val target = targetDevice()
        val report = target.engine().restore(backupOf(sourceDevice()))

        val settings = target.prefs.files.getValue("meowgi_launcher710_settings")
        val newIcon = JSONArray(settings["pageIntentShortcuts_p1"] as String).getJSONObject(0).getString("iconFile")
        assertTrue(newIcon, newIcon.startsWith("$root/files/backup_assets/"))
        assertArrayEquals(iconBytes, target.assets.files.getValue(newIcon))
        assertEquals(
            sourceDevice().prefs.files.getValue("meowgi_launcher710_settings") - "pageIntentShortcuts_p1",
            settings - "pageIntentShortcuts_p1"
        )
        assertEquals(shortcutsJson(newIcon), settings["pageIntentShortcuts_p1"])
        assertEquals(mapOf("dock_unified_order" to "app:com.a/.Main"), target.prefs.files["dock"])
        assertEquals(mapOf("keep" to true), target.prefs.files["unrelated"])
        assertEquals(sourceDevice().db.tables["app_stats"], target.db.tables["app_stats"])

        assertEquals(RestoreReport(2, 7, 1, 1, 1, emptyList(), emptyList()), report)
        assertNull(target.journal.pending())
    }

    @Test
    fun failedPrefsWriteLeavesEverythingUntouched() {
        val target = targetDevice()
        val before = target.state()
        target.prefs.failOnWrite = 2
        expectRestoreFails(target, "Nothing was changed")
        assertEquals(before, target.state())
        assertNull(target.journal.pending())
        assertTrue(target.assets.staged.isEmpty())
    }

    @Test
    fun failedDatabaseReplaceLeavesEverythingUntouched() {
        val target = targetDevice()
        val before = target.state()
        target.db.failOnReplace = "app_stats"
        expectRestoreFails(target, "Nothing was changed")
        assertEquals(before, target.state())
        assertNull(target.journal.pending())
    }

    @Test
    fun failedAssetCommitLeavesEverythingUntouched() {
        val target = targetDevice()
        val before = target.state()
        target.assets.failOnCommit = true
        expectRestoreFails(target, "Nothing was changed")
        assertEquals(before, target.state())
        assertTrue(target.assets.staged.isEmpty())
    }

    @Test
    fun interruptedRestoreIsRolledBackOnNextStart() {
        val target = targetDevice()
        val before = target.state()
        val backup = backupOf(sourceDevice())
        // Storage dies after the first prefs file lands, so the in-process rollback can't write it back either.
        var writes = 0
        val dying = object : PrefsStore by target.prefs {
            override fun write(name: String, values: Map<String, Any>) {
                if (++writes >= 2) throw IOException("process killed")
                target.prefs.write(name, values)
            }
        }
        val engine = BackupEngine(dying, target.db, target.assets, target.journal, listOf(root), BackupSource(pkg, null, 0))
        expectFails("next start") { engine.restore(backup) }
        assertNotNull("journal must survive a failed rollback", target.journal.pending())
        assertNotEquals(before, target.state())

        assertTrue(target.engine().recoverInterrupted())
        assertEquals(before, target.state())
        assertNull(target.journal.pending())
        assertFalse(target.engine().recoverInterrupted())
    }

    @Test
    fun unknownTablesAndColumnsAreSkippedAndNewColumnsSeeded() {
        val oldSchema = mapOf(
            "app_stats" to listOf("componentName", "launchCount", "isFavorite", "lastLaunched", "legacyFlag"),
            "old_table" to listOf("id")
        )
        val old = Device(
            tables = linkedMapOf(
                "app_stats" to TableData(oldSchema.getValue("app_stats"), listOf(listOf("com.a/com.a.Main", 6L, 0L, 77L, 1L))),
                "old_table" to TableData(listOf("id"), listOf(listOf(1L)))
            ),
            schema = oldSchema
        )
        val target = targetDevice()
        val report = target.engine().restore(backupOf(old))
        assertEquals(listOf("old_table"), report.skippedTables)
        assertEquals(listOf("app_stats.legacyFlag"), report.droppedColumns)
        assertEquals(
            TableData(statsColumns, listOf(listOf("com.a/com.a.Main", 6L, 0L, 77L, 6L, 77L))),
            target.db.tables["app_stats"]
        )
    }

    @Test
    fun migratedLegacyFileOnlyTouchesSettings() {
        val legacy = """{"version":1,"entries":[{"k":"accentColor","t":"i","v":5},{"k":"iconPackPackage","t":"s","v":""}]}"""
        val target = targetDevice()
        val before = target.state()
        val report = target.engine().restore(BackupCodec.decode(legacy.toByteArray()))
        assertEquals(mapOf("accentColor" to 5), target.prefs.files["meowgi_launcher710_settings"])
        assertEquals(before.first["dock"], target.prefs.files["dock"])
        assertEquals(before.second, target.db.tables)
        assertEquals(1, report.prefFiles)
        assertEquals(0, report.tables)
    }

    @Test
    fun nothingPendingMeansNoRecovery() {
        assertFalse(targetDevice().engine().recoverInterrupted())
    }

    private fun expectRestoreFails(target: Device, fragment: String) =
        expectFails(fragment) { target.engine().restore(backupOf(sourceDevice())) }

    private fun expectFails(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure")
        } catch (e: BackupException) {
            assertTrue("\"${e.message}\" should contain \"$fragment\"", e.message!!.contains(fragment))
        }
    }
}
