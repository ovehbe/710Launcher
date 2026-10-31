package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup file: a zip holding `manifest.json` plus one entry per prefs file
 * (`prefs/<name>.json`), per database table (`db/<table>.json`) and per asset (`assets/<name>`).
 * The manifest lists every other entry with its size and SHA-256.
 *
 * Pure JVM (no Android framework) so it is unit-tested directly. [decode] validates the whole
 * file and throws [BackupException] before returning; it never returns a partially valid backup.
 */
object BackupCodec {
    const val FORMAT = "com.meowgi.launcher710.backup"
    /** 1 = the pre-zip "Export settings" JSON (settings prefs only), migrated on read. 2 = zip. */
    const val CURRENT_SCHEMA_VERSION = 2
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val SETTINGS_PREFS = "meowgi_launcher710_settings"

    const val MANIFEST = "manifest.json"
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    const val MAX_ASSET_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 2048

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    /** Table and column names end up in SQL, so they are held to plain identifiers. */
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    /** Undecoded file: the manifest plus entry bytes by path. Migrations operate on this form. */
    class RawBackup(val manifest: JSONObject, val entries: Map<String, ByteArray>)

    /**
     * fromVersion → step to fromVersion + 1. When the schema is bumped, add a step here that
     * rewrites a RawBackup of the previous version (manifest and entries) into the new layout.
     */
    private val migrations: Map<Int, (RawBackup) -> RawBackup> = mapOf(
        1 to ::migrateV1ToV2
    )

    // ---------------------------------------------------------------- encode

    fun encode(p: BackupPayload): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        for ((name, values) in p.prefs.toSortedMap()) {
            requireName(name, "preference file")
            val obj = JSONObject()
            for ((k, v) in values.toSortedMap()) encodePref(v)?.let { obj.put(k, it) }
            entries["prefs/$name.json"] = obj.toString().toByteArray(Charsets.UTF_8)
        }
        for ((table, data) in p.tables.toSortedMap()) {
            requireIdentifier(table, "table")
            data.columns.forEach { requireIdentifier(it, "column") }
            val rows = JSONArray()
            for (row in data.rows) rows.put(JSONArray().apply { row.forEach { put(encodeCell(it)) } })
            val obj = JSONObject().put("columns", JSONArray(data.columns)).put("rows", rows)
            entries["db/$table.json"] = obj.toString().toByteArray(Charsets.UTF_8)
        }
        val assets = JSONArray()
        for (a in p.assets) {
            requireName(a.name, "asset")
            entries["assets/${a.name}"] = a.bytes
            assets.put(JSONObject().put("path", "assets/${a.name}").put("originalPath", a.originalPath))
        }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("createdAt", p.createdAtMillis)
            put("app", JSONObject().apply {
                put("package", p.source.packageName ?: JSONObject.NULL)
                put("versionName", p.source.versionName ?: JSONObject.NULL)
                put("versionCode", p.source.versionCode)
            })
            put("prefs", JSONArray(p.prefs.keys.sorted()))
            put("tables", JSONArray(p.tables.keys.sorted()))
            put("assets", assets)
            put("entries", entryList(entries))
            put("counts", JSONObject().apply {
                put("prefFiles", p.prefs.size)
                put("prefKeys", p.prefKeyCount)
                put("tables", p.tables.size)
                put("rows", p.rowCount)
                put("assets", p.assets.size)
            })
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entryList(entries: Map<String, ByteArray>) = JSONArray().apply {
        for ((path, bytes) in entries) {
            put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", sha256Hex(bytes)))
        }
    }

    private fun encodePref(v: Any): JSONObject? = when (v) {
        is String -> typed("s", v)
        is Int -> typed("i", v)
        is Long -> typed("l", v)
        is Float -> if (v.isFinite()) typed("f", v.toDouble()) else null
        is Boolean -> typed("b", v)
        is Set<*> -> typed("set", JSONArray(v.map { it.toString() }.sorted()))
        else -> null
    }

    private fun typed(t: String, v: Any) = JSONObject().put("t", t).put("v", v)

    private fun encodeCell(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Long -> JSONObject().put("i", v)
        is Int -> JSONObject().put("i", v.toLong())
        is Double -> if (v.isFinite()) JSONObject().put("r", v) else JSONObject.NULL
        is Float -> if (v.isFinite()) JSONObject().put("r", v.toDouble()) else JSONObject.NULL
        is String -> JSONObject().put("s", v)
        is Blob -> JSONObject().put("b", Base64.getEncoder().encodeToString(v.bytes))
        else -> throw BackupException("Unsupported database value ${v::class.java.simpleName}.")
    }

    // ---------------------------------------------------------------- decode

    fun decode(bytes: ByteArray): BackupPayload {
        if (bytes.isEmpty()) throw BackupException("The file is empty.")
        if (bytes.size > MAX_FILE_BYTES) throw BackupException("The file is too large to be a 710 Launcher backup.")
        var raw = if (isZip(bytes)) readZip(bytes) else readLegacy(bytes)
        val version = intField(raw.manifest, "schemaVersion")
        if (version > CURRENT_SCHEMA_VERSION) {
            throw BackupException("This backup was made by a newer version of 710 Launcher (format $version). Update the app to restore it.")
        }
        if (version < MIN_SUPPORTED_SCHEMA_VERSION) throw BackupException("This backup uses an unsupported format ($version).")
        for (v in version until CURRENT_SCHEMA_VERSION) {
            val step = migrations[v] ?: throw BackupException("This backup's format ($v) can't be upgraded.")
            raw = step(raw)
        }
        return try {
            parseCurrent(raw, version)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw corrupt(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun corrupt(detail: String, cause: Throwable? = null) =
        BackupException("The backup file is damaged or incomplete ($detail).", cause)

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
        b[2] == 3.toByte() && b[3] == 4.toByte()

    /** Reads every entry (bounded), then checks the manifest's entry list: sizes, checksums, nothing missing or extra. */
    private fun readZip(bytes: ByteArray): RawBackup {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var entryCount = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val e = zip.nextEntry ?: break
                    entryCount++
                    if (e.isDirectory) continue
                    if (entries.size >= MAX_ENTRIES) throw corrupt("too many entries")
                    val name = e.name
                    if (name in entries) throw corrupt("duplicate entry $name")
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (out.size() > MAX_ENTRY_BYTES || total > MAX_FILE_BYTES) throw corrupt("entry $name is too large")
                    }
                    entries[name] = out.toByteArray()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw corrupt("unreadable zip: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw corrupt("unreadable zip: ${e.message}", e)
        }
        checkEndRecord(bytes, entryCount)
        val manifestBytes = entries.remove(MANIFEST) ?: throw corrupt("no manifest")
        val manifest = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw corrupt("manifest is not valid JSON", e)
        }
        if (manifest.opt("format") != FORMAT) throw BackupException("This is not a 710 Launcher backup.")
        intField(manifest, "schemaVersion")
        val listed = manifest.optJSONArray("entries") ?: throw corrupt("manifest has no entry list")
        val seen = HashSet<String>()
        for (i in 0 until listed.length()) {
            val o = listed.optJSONObject(i) ?: throw corrupt("manifest entry $i")
            val path = o.opt("path") as? String ?: throw corrupt("manifest entry $i has no path")
            if (!seen.add(path)) throw corrupt("$path listed twice")
            val data = entries[path] ?: throw corrupt("$path is missing")
            val size = o.opt("size")
            if (size !is Number || size.toLong() != data.size.toLong()) throw corrupt("$path has the wrong size")
            val sha = o.opt("sha256") as? String
            if (sha == null || !sha.equals(sha256Hex(data), ignoreCase = true)) throw corrupt("$path failed its checksum")
        }
        val extra = entries.keys - seen
        if (extra.isNotEmpty()) throw corrupt("unexpected entry ${extra.first()}")
        return RawBackup(manifest, entries)
    }

    /**
     * ZipInputStream stops at the first central-directory header, so a file cut inside the
     * directory would still read cleanly. The end record must be present, end the file exactly,
     * and agree with the directory's position and the number of entries read.
     */
    private fun checkEndRecord(b: ByteArray, entryCount: Int) {
        fun u16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        val last = b.size - 22
        var at = -1
        var i = last
        while (i >= 0 && i >= last - 0xffff) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4b.toByte() && b[i + 2] == 5.toByte() && b[i + 3] == 6.toByte() &&
                i + 22 + u16(i + 20) == b.size
            ) { at = i; break }
            i--
        }
        if (at < 0) throw corrupt("the file is truncated")
        if (u16(at + 10) != entryCount || u32(at + 12) + u32(at + 16) != at.toLong()) {
            throw corrupt("the zip directory doesn't match its contents")
        }
    }

    /** The pre-zip settings export: {"entries":[{"k","t","v"}...], "version":1}. */
    private fun readLegacy(bytes: ByteArray): RawBackup {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw BackupException("This is not a 710 Launcher backup (or the file is damaged).", e)
        }
        if (!root.has("entries") || !root.has("version")) throw BackupException("This is not a 710 Launcher backup.")
        val manifest = JSONObject().put("format", FORMAT).put("schemaVersion", intField(root, "version"))
        return RawBackup(manifest, mapOf("legacy.json" to bytes))
    }

    /**
     * 1 → 2. The legacy file held only the settings prefs as a typed entry list and wrote unset
     * strings as "" — dropping those restores "unset", which every reader already treats "" as.
     * No dock or database section, so restoring it leaves both untouched, as the old import did.
     */
    private fun migrateV1ToV2(v1: RawBackup): RawBackup {
        val root = JSONObject(String(v1.entries.getValue("legacy.json"), Charsets.UTF_8))
        val list = root.optJSONArray("entries") ?: throw corrupt("no entries")
        val settings = JSONObject()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: throw corrupt("entry $i is malformed")
            val k = o.opt("k") as? String ?: throw corrupt("entry $i has no key")
            val t = o.opt("t") as? String ?: throw corrupt("entry $i has no type")
            val v = o.opt("v")
            if (t == "s" && v == "") continue
            settings.put(k, JSONObject().put("t", t).put("v", v ?: JSONObject.NULL))
        }
        val entries = mapOf("prefs/$SETTINGS_PREFS.json" to settings.toString().toByteArray(Charsets.UTF_8))
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", 2)
            put("createdAt", 0L)
            put("prefs", JSONArray(listOf(SETTINGS_PREFS)))
            put("tables", JSONArray())
            put("assets", JSONArray())
            put("entries", entryList(entries))
        }
        return RawBackup(manifest, entries)
    }

    private fun parseCurrent(raw: RawBackup, originalVersion: Int): BackupPayload {
        val m = raw.manifest
        val used = HashSet<String>()
        fun entry(path: String): ByteArray {
            used += path
            return raw.entries[path] ?: throw corrupt("$path is missing")
        }
        fun json(path: String) = try {
            JSONObject(String(entry(path), Charsets.UTF_8))
        } catch (e: JSONException) {
            throw corrupt("$path is not valid JSON", e)
        }

        val prefs = LinkedHashMap<String, Map<String, Any>>()
        for (name in stringList(m, "prefs")) {
            requireName(name, "preference file")
            if (name in prefs) throw corrupt("preference file $name listed twice")
            val obj = json("prefs/$name.json")
            val values = LinkedHashMap<String, Any>()
            for (key in obj.keys()) {
                if (key.isEmpty()) throw corrupt("empty preference key in $name")
                val e = obj.optJSONObject(key) ?: throw corrupt("preference $key is malformed")
                values[key] = decodePref(key, e)
            }
            prefs[name] = values
        }

        val tables = LinkedHashMap<String, TableData>()
        for (table in stringList(m, "tables")) {
            requireIdentifier(table, "table")
            if (table in tables) throw corrupt("table $table listed twice")
            val obj = json("db/$table.json")
            val cols = obj.optJSONArray("columns") ?: throw corrupt("table $table has no columns")
            val columns = (0 until cols.length()).map { i ->
                (cols.opt(i) as? String)?.also { requireIdentifier(it, "column") } ?: throw corrupt("table $table column $i")
            }
            if (columns.isEmpty() || columns.toSet().size != columns.size) throw corrupt("table $table has bad columns")
            val rowsArr = obj.optJSONArray("rows") ?: throw corrupt("table $table has no rows")
            val rows = (0 until rowsArr.length()).map { r ->
                val row = rowsArr.optJSONArray(r) ?: throw corrupt("table $table row $r")
                if (row.length() != columns.size) throw corrupt("table $table row $r has ${row.length()} cells")
                (0 until row.length()).map { c -> decodeCell(row.opt(c), "table $table row $r") }
            }
            tables[table] = TableData(columns, rows)
        }

        val assetsArr = m.optJSONArray("assets") ?: JSONArray()
        val names = HashSet<String>()
        val assets = (0 until assetsArr.length()).map { i ->
            val o = assetsArr.optJSONObject(i) ?: throw corrupt("asset $i is malformed")
            val path = o.opt("path") as? String ?: throw corrupt("asset $i has no path")
            val name = path.removePrefix("assets/")
            if (!path.startsWith("assets/")) throw corrupt("asset $i path")
            requireName(name, "asset")
            if (!names.add(name)) throw corrupt("asset $name listed twice")
            val original = o.opt("originalPath") as? String
            if (original.isNullOrEmpty() || !original.startsWith("/")) throw corrupt("asset $name has no original path")
            val bytes = entry(path)
            if (bytes.size > MAX_ASSET_BYTES) throw corrupt("asset $name is too large")
            BackupAsset(name, original, bytes)
        }

        val unused = raw.entries.keys - used
        if (unused.isNotEmpty()) throw corrupt("unexpected entry ${unused.first()}")

        val app = m.optJSONObject("app")
        return BackupPayload(
            createdAtMillis = (m.opt("createdAt") as? Number)?.toLong() ?: 0L,
            source = BackupSource(
                packageName = app?.opt("package") as? String,
                versionName = app?.opt("versionName") as? String,
                versionCode = (app?.opt("versionCode") as? Number)?.toLong() ?: 0L
            ),
            prefs = prefs,
            tables = tables,
            assets = assets,
            originalSchemaVersion = originalVersion
        )
    }

    private fun stringList(m: JSONObject, field: String): List<String> {
        val arr = m.optJSONArray(field) ?: throw corrupt("manifest has no $field list")
        return (0 until arr.length()).map { arr.opt(it) as? String ?: throw corrupt("manifest $field[$it]") }
    }

    private fun decodePref(key: String, e: JSONObject): Any {
        val v = e.opt("v")
        fun bad(): Nothing = throw corrupt("preference $key has an invalid value")
        return when (e.opt("t")) {
            "s" -> v as? String ?: bad()
            "i" -> if (v is Number && isIntegral(v) && v.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else bad()
            "l" -> if (v is Number && isIntegral(v)) v.toLong() else bad()
            "f" -> if (v is Number && v.toDouble().isFinite()) v.toFloat() else bad()
            "b" -> v as? Boolean ?: bad()
            "set" -> {
                val arr = v as? JSONArray ?: bad()
                (0 until arr.length()).mapTo(LinkedHashSet()) { arr.opt(it) as? String ?: bad() }
            }
            else -> throw corrupt("preference $key has an unknown type")
        }
    }

    private fun decodeCell(v: Any?, where: String): Any? {
        if (v == null || v == JSONObject.NULL) return null
        val o = v as? JSONObject ?: throw corrupt("$where has an invalid cell")
        if (o.length() != 1) throw corrupt("$where has an invalid cell")
        return when {
            o.has("i") -> (o.opt("i") as? Number)?.takeIf { isIntegral(it) }?.toLong()
            o.has("r") -> (o.opt("r") as? Number)?.toDouble()?.takeIf { it.isFinite() }
            o.has("s") -> o.opt("s") as? String
            o.has("b") -> (o.opt("b") as? String)?.let {
                try { Blob(Base64.getDecoder().decode(it)) } catch (e: IllegalArgumentException) { null }
            }
            else -> null
        } ?: throw corrupt("$where has an invalid cell")
    }

    private fun intField(o: JSONObject, field: String): Int {
        val v = o.opt(field)
        if (v !is Number || !isIntegral(v)) throw corrupt("$field is missing or invalid")
        return v.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isIntegral(n: Number): Boolean = when (n) {
        is Int, is Long, is Short, is Byte, is BigInteger -> true
        is BigDecimal -> n.stripTrailingZeros().scale() <= 0
        else -> n.toDouble().let { it.isFinite() && it == Math.floor(it) }
    }

    fun isValidName(name: String) = NAME.matches(name) && !name.contains("..")

    private fun requireName(name: String, what: String) {
        if (!isValidName(name)) throw corrupt("invalid $what name \"$name\"")
    }

    private fun requireIdentifier(name: String, what: String) {
        if (!IDENTIFIER.matches(name)) throw corrupt("invalid $what name \"$name\"")
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
