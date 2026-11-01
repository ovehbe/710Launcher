package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCodecTest {

    private val payload = BackupPayload(
        createdAtMillis = 1_700_000_000_000L,
        source = BackupSource("com.meowgi.launcher710", "1.5.5", 18),
        prefs = mapOf(
            "meowgi_launcher710_settings" to mapOf(
                "accentColor" to -16733696,
                "headerVisible" to true,
                "fontScale" to 1.25f,
                "installTime" to 9_000_000_000_000L,
                "iconPackPackage" to "com.example.icons",
                "listViewPages" to setOf("all", "fav"),
                "emptySet" to emptySet<String>(),
                "unicode" to "ä \"quoted\" \\ / \n"
            ),
            "dock" to mapOf("dock_unified_order" to "app:com.a/.Main,app:com.b/.Main")
        ),
        tables = mapOf(
            "app_stats" to TableData(
                listOf("componentName", "launchCount", "isFavorite", "lastLaunched", "frecency", "extra"),
                listOf(
                    listOf("com.a/com.a.Main", 3L, 1L, 1_699_999_000_000L, 2.75, null),
                    listOf("com.b/com.b.Main", 0L, 0L, 0L, 0.0, Blob(byteArrayOf(0, 1, -1)))
                )
            )
        ),
        assets = listOf(BackupAsset("0_shortcut_icon_1.png", "/data/user/0/com.meowgi.launcher710/cache/shortcut_icon_1.png", byteArrayOf(-119, 80, 78, 71, 0, 7)))
    )

    @Test
    fun roundTripKeepsEveryTypeAndValue() {
        val decoded = BackupCodec.decode(BackupCodec.encode(payload))
        assertEquals(payload, decoded)
        assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, decoded.originalSchemaVersion)
    }

    @Test
    fun manifestDescribesTheFile() {
        val entries = unzip(BackupCodec.encode(payload))
        assertEquals(BackupCodec.MANIFEST, entries.keys.first())
        val m = JSONObject(String(entries.getValue(BackupCodec.MANIFEST)))
        assertEquals(BackupCodec.FORMAT, m.getString("format"))
        assertEquals(BackupCodec.CURRENT_SCHEMA_VERSION, m.getInt("schemaVersion"))
        assertEquals(entries.size - 1, m.getJSONArray("entries").length())
        val counts = m.getJSONObject("counts")
        assertEquals(9, counts.getInt("prefKeys"))
        assertEquals(2, counts.getInt("rows"))
        assertEquals(1, counts.getInt("assets"))
    }

    @Test
    fun emptyAndGarbageFilesAreRejected() {
        expectError(ByteArray(0), "empty")
        expectError("hello".toByteArray(), "not a 710 Launcher backup")
        expectError(byteArrayOf(0x50, 0x4b, 3, 4, 9, 9, 9), "damaged")
        expectError("{\"some\":\"json\"}".toByteArray(), "not a 710 Launcher backup")
    }

    @Test
    fun everyTruncationIsRejected() {
        val bytes = BackupCodec.encode(payload)
        for (cut in 1 until bytes.size) {
            try {
                BackupCodec.decode(bytes.copyOf(cut))
                fail("cut at $cut of ${bytes.size} was accepted")
            } catch (e: BackupException) {
                // expected
            }
        }
    }

    @Test
    fun tamperedEntryFailsItsChecksum() {
        val entries = unzip(BackupCodec.encode(payload))
        val path = "prefs/dock.json"
        entries[path] = String(entries.getValue(path)).replace("com.a", "com.z").toByteArray()
        expectError(zip(entries), "failed its checksum")
    }

    @Test
    fun missingOrUnlistedEntriesAreRejected() {
        val missing = unzip(BackupCodec.encode(payload)).apply { remove("db/app_stats.json") }
        expectError(zip(missing), "db/app_stats.json is missing")

        val extra = unzip(BackupCodec.encode(payload)).apply { put("prefs/sneaky.json", "{}".toByteArray()) }
        expectError(zip(extra), "unexpected entry prefs/sneaky.json")

        val noManifest = unzip(BackupCodec.encode(payload)).apply { remove(BackupCodec.MANIFEST) }
        expectError(zip(noManifest), "no manifest")
    }

    @Test
    fun pathTraversalNamesAreRejectedEvenWithValidChecksums() {
        val evil = "../../shared_prefs/x"
        val entries = linkedMapOf("prefs/$evil.json" to "{}".toByteArray())
        expectError(zip(withManifest(entries, prefs = listOf(evil))), "invalid preference file name")
    }

    @Test
    fun malformedContentIsRejected() {
        val badRow = linkedMapOf("db/t.json" to """{"columns":["a","b"],"rows":[[{"i":1}]]}""".toByteArray())
        expectError(zip(withManifest(badRow, tables = listOf("t"))), "has 1 cells")

        val badType = linkedMapOf("prefs/p.json" to """{"k":{"t":"i","v":"seven"}}""".toByteArray())
        expectError(zip(withManifest(badType, prefs = listOf("p"))), "invalid value")

        val badSql = linkedMapOf("db/x;DROP.json" to """{"columns":["a"],"rows":[]}""".toByteArray())
        expectError(zip(withManifest(badSql, tables = listOf("x;DROP"))), "invalid table name")
    }

    @Test
    fun newerVersionIsRejectedWithUpdateHint() {
        val entries = linkedMapOf("prefs/p.json" to "{}".toByteArray())
        expectError(zip(withManifest(entries, prefs = listOf("p"), version = BackupCodec.CURRENT_SCHEMA_VERSION + 1)), "newer version")
    }

    @Test
    fun wrongFormatAndAncientVersionAreRejected() {
        val entries = linkedMapOf("prefs/p.json" to "{}".toByteArray())
        expectError(zip(withManifest(entries, prefs = listOf("p"), format = "com.other.app")), "not a 710 Launcher backup")
        expectError(zip(withManifest(entries, prefs = listOf("p"), version = 0)), "unsupported format")
    }

    @Test
    fun legacySettingsExportIsMigrated() {
        val legacy = JSONObject().put("version", 1).put("entries", JSONArray().apply {
            put(JSONObject().put("k", "accentColor").put("t", "i").put("v", 42))
            put(JSONObject().put("k", "headerVisible").put("t", "b").put("v", false))
            put(JSONObject().put("k", "fontScale").put("t", "f").put("v", 1.5))
            put(JSONObject().put("k", "installTime").put("t", "l").put("v", 5L))
            put(JSONObject().put("k", "iconPackPackage").put("t", "s").put("v", ""))
            put(JSONObject().put("k", "searchEngine").put("t", "s").put("v", "ddg"))
            put(JSONObject().put("k", "listViewPages").put("t", "set").put("v", JSONArray(listOf("all"))))
        })
        val decoded = BackupCodec.decode(legacy.toString().toByteArray())
        assertEquals(1, decoded.originalSchemaVersion)
        assertEquals(setOf(BackupCodec.SETTINGS_PREFS), decoded.prefs.keys)
        assertEquals(
            mapOf(
                "accentColor" to 42, "headerVisible" to false, "fontScale" to 1.5f, "installTime" to 5L,
                "searchEngine" to "ddg", "listViewPages" to setOf("all")
            ),
            decoded.prefs.getValue(BackupCodec.SETTINGS_PREFS)
        )
        assertTrue(decoded.tables.isEmpty())
        assertTrue(decoded.assets.isEmpty())
    }

    @Test
    fun corruptLegacyEntryIsRejected() {
        val legacy = """{"version":1,"entries":[{"k":"a","t":"i","v":"x"}]}"""
        expectError(legacy.toByteArray(), "invalid value")
    }

    // ------------------------------------------------------------------ helpers

    private fun expectError(bytes: ByteArray, fragment: String) {
        try {
            BackupCodec.decode(bytes)
            fail("expected an error containing \"$fragment\"")
        } catch (e: BackupException) {
            assertTrue("\"${e.message}\" should contain \"$fragment\"", e.message!!.contains(fragment))
        }
    }

    private fun withManifest(
        entries: LinkedHashMap<String, ByteArray>,
        prefs: List<String> = emptyList(),
        tables: List<String> = emptyList(),
        version: Int = BackupCodec.CURRENT_SCHEMA_VERSION,
        format: String = BackupCodec.FORMAT
    ): LinkedHashMap<String, ByteArray> {
        val list = JSONArray()
        for ((path, bytes) in entries) {
            list.put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", BackupCodec.sha256Hex(bytes)))
        }
        val m = JSONObject().put("format", format).put("schemaVersion", version).put("createdAt", 1L)
            .put("prefs", JSONArray(prefs)).put("tables", JSONArray(tables)).put("assets", JSONArray()).put("entries", list)
        return LinkedHashMap<String, ByteArray>().apply {
            put(BackupCodec.MANIFEST, m.toString().toByteArray())
            putAll(entries)
        }
    }

    companion object {
        fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    out[e.name] = z.readBytes()
                }
            }
            return out
        }

        fun zip(entries: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                for ((name, bytes) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}
