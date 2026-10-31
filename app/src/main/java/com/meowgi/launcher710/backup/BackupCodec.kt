package com.meowgi.launcher710.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class AppStatRecord(
    val componentName: String,
    val launchCount: Int,
    val isFavorite: Boolean,
    val lastLaunched: Long,
    /** Decayed launch score as of [frecencyAt]. Absent in a file → seeded from count and time. */
    val frecency: Double = launchCount.toDouble(),
    val frecencyAt: Long = lastLaunched
)

/** A binary that prefs point at by absolute path (e.g. a pinned intent shortcut's icon). */
class BackupFile(val name: String, val originalPath: String?, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is BackupFile && name == other.name && originalPath == other.originalPath && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (name.hashCode() * 31 + (originalPath?.hashCode() ?: 0)) * 31 + bytes.contentHashCode()
}

/**
 * Everything a restore needs. Pref values are String, Int, Long, Float, Boolean or Set<String>.
 * Only the pref files present in [prefs] are replaced on restore, and [appStats] == null leaves
 * the database alone — that is how a migrated settings-only legacy file keeps its old meaning.
 */
data class LauncherBackup(
    val schemaVersion: Int = BackupCodec.CURRENT_SCHEMA_VERSION,
    val createdAtMillis: Long,
    val appVersionName: String?,
    val appVersionCode: Long,
    val sourcePackage: String?,
    val prefs: Map<String, Map<String, Any>>,
    val appStats: List<AppStatRecord>?,
    val files: List<BackupFile>
)

/**
 * Pure (no Android framework) encoder/decoder for the backup file, so it is unit-testable on the
 * JVM. [decode] validates the whole document and throws [BackupException] before anything is
 * applied; it never returns a partially valid backup.
 */
object BackupCodec {
    const val FORMAT = "com.meowgi.launcher710.backup"
    const val CURRENT_SCHEMA_VERSION = 2
    /** Schema 1 is the pre-1.6 "Export settings" file (settings prefs only), migrated on read. */
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1

    const val SETTINGS_PREFS = "meowgi_launcher710_settings"
    const val DOCK_PREFS = "dock"
    val PREF_FILES: Set<String> = setOf(SETTINGS_PREFS, DOCK_PREFS)

    const val MAX_BACKUP_BYTES = 32 * 1024 * 1024
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_FILES = 512
    private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

    /** fromVersion → step to fromVersion + 1. Add an entry here whenever the schema is bumped. */
    private val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
        1 to ::migrateV1ToV2
    )

    fun encode(backup: LauncherBackup): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("createdAt", backup.createdAtMillis)
        root.put("app", JSONObject().apply {
            put("versionName", backup.appVersionName ?: JSONObject.NULL)
            put("versionCode", backup.appVersionCode)
            put("package", backup.sourcePackage ?: JSONObject.NULL)
        })
        val prefsObj = JSONObject()
        for ((file, values) in backup.prefs.toSortedMap()) {
            val fileObj = JSONObject()
            for ((key, value) in values.toSortedMap()) {
                encodeValue(value)?.let { fileObj.put(key, it) }
            }
            prefsObj.put(file, fileObj)
        }
        root.put("prefs", prefsObj)
        backup.appStats?.let { stats ->
            val arr = JSONArray()
            for (s in stats.sortedBy { it.componentName }) {
                arr.put(JSONObject().apply {
                    put("componentName", s.componentName)
                    put("launchCount", s.launchCount)
                    put("isFavorite", s.isFavorite)
                    put("lastLaunched", s.lastLaunched)
                    if (s.frecency.isFinite()) put("frecency", s.frecency)
                    put("frecencyAt", s.frecencyAt)
                })
            }
            root.put("database", JSONObject().put("app_stats", arr))
        }
        val files = JSONArray()
        for (f in backup.files) {
            files.put(JSONObject().apply {
                put("name", f.name)
                put("originalPath", f.originalPath ?: JSONObject.NULL)
                put("sha256", sha256Hex(f.bytes))
                put("data", Base64.getEncoder().encodeToString(f.bytes))
            })
        }
        root.put("files", files)
        return root.toString(2)
    }

    /** Non-finite floats have no JSON representation; such a value is dropped (it can't be set from the UI). */
    private fun encodeValue(value: Any): JSONObject? = when (value) {
        is String -> typed("s", value)
        is Int -> typed("i", value)
        is Long -> typed("l", value)
        is Float -> if (value.isFinite()) typed("f", value.toDouble()) else null
        is Double -> if (value.isFinite()) typed("f", value) else null
        is Boolean -> typed("b", value)
        is Set<*> -> typed("set", JSONArray(value.map { it.toString() }.sorted()))
        else -> null
    }

    private fun typed(t: String, v: Any) = JSONObject().put("t", t).put("v", v)

    fun decode(json: String): LauncherBackup {
        if (json.isBlank()) throw BackupException("The file is empty.")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupException("The file is not valid JSON (corrupt or truncated).", e)
        }
        val version = detectVersion(root)
        if (version > CURRENT_SCHEMA_VERSION) {
            throw BackupException(
                "This backup was made by a newer version of 710 Launcher (schema $version). Update the app to restore it."
            )
        }
        if (version < MIN_SUPPORTED_SCHEMA_VERSION) {
            throw BackupException("This backup uses an unsupported old format (schema $version).")
        }
        var migrated = root
        for (v in version until CURRENT_SCHEMA_VERSION) {
            val step = migrations[v] ?: throw BackupException("No migration from schema $v.")
            migrated = step(migrated)
        }
        return try {
            parseCurrent(migrated)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup is corrupt: ${e.message}", e)
        }
    }

    private fun detectVersion(root: JSONObject): Int {
        if (root.has("format")) {
            if (root.opt("format") != FORMAT) throw BackupException("This is not a 710 Launcher backup.")
            return intOrThrow(root.opt("schemaVersion"), "schemaVersion")
        }
        // Legacy "Export settings" file: {"entries":[...], "version":1}
        if (root.has("entries") && root.has("version")) return intOrThrow(root.opt("version"), "version")
        throw BackupException("This is not a 710 Launcher backup.")
    }

    private fun intOrThrow(v: Any?, field: String): Int {
        if (v !is Number || !isIntegral(v)) throw BackupException("The backup is corrupt: $field is missing or invalid.")
        return v.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseCurrent(root: JSONObject): LauncherBackup {
        val app = root.optJSONObject("app")
        val prefsObj = root.optJSONObject("prefs") ?: throw BackupException("The backup is corrupt: no preferences section.")
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        for (file in prefsObj.keys()) {
            if (file !in PREF_FILES) throw BackupException("The backup is corrupt: unknown preference file \"$file\".")
            val fileObj = prefsObj.optJSONObject(file)
                ?: throw BackupException("The backup is corrupt: preference file \"$file\" is not an object.")
            val values = LinkedHashMap<String, Any>()
            for (key in fileObj.keys()) {
                if (key.isEmpty()) throw BackupException("The backup is corrupt: empty preference key in \"$file\".")
                val entry = fileObj.optJSONObject(key)
                    ?: throw BackupException("The backup is corrupt: preference \"$key\" is malformed.")
                values[key] = decodeValue(key, entry)
            }
            prefs[file] = values
        }

        val stats = root.optJSONObject("database")?.let { db ->
            val arr = db.optJSONArray("app_stats")
                ?: throw BackupException("The backup is corrupt: database section has no app_stats.")
            val seen = HashSet<String>()
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw BackupException("The backup is corrupt: app_stats row $i is malformed.")
                val cn = o.opt("componentName") as? String
                if (cn.isNullOrBlank() || '/' !in cn) throw BackupException("The backup is corrupt: app_stats row $i has an invalid component.")
                if (!seen.add(cn)) throw BackupException("The backup is corrupt: duplicate app_stats row for $cn.")
                val count = o.opt("launchCount")
                val fav = o.opt("isFavorite")
                val last = o.opt("lastLaunched")
                if (count !is Number || !isIntegral(count) || count.toLong() !in 0..Int.MAX_VALUE.toLong()) {
                    throw BackupException("The backup is corrupt: app_stats row $i has an invalid launch count.")
                }
                if (fav !is Boolean) throw BackupException("The backup is corrupt: app_stats row $i has an invalid favorite flag.")
                if (last !is Number || !isIntegral(last) || last.toLong() < 0) {
                    throw BackupException("The backup is corrupt: app_stats row $i has an invalid timestamp.")
                }
                val frecency = o.opt("frecency")
                val frecencyAt = o.opt("frecencyAt")
                if (frecency != null && (frecency !is Number || !frecency.toDouble().isFinite() || frecency.toDouble() < 0)) {
                    throw BackupException("The backup is corrupt: app_stats row $i has an invalid usage score.")
                }
                if (frecencyAt != null && (frecencyAt !is Number || !isIntegral(frecencyAt) || frecencyAt.toLong() < 0)) {
                    throw BackupException("The backup is corrupt: app_stats row $i has an invalid usage timestamp.")
                }
                AppStatRecord(
                    cn, count.toInt(), fav, last.toLong(),
                    frecency = (frecency as? Number)?.toDouble() ?: count.toDouble(),
                    frecencyAt = (frecencyAt as? Number)?.toLong() ?: last.toLong()
                )
            }
        }

        val filesArr = root.optJSONArray("files") ?: JSONArray()
        if (filesArr.length() > MAX_FILES) throw BackupException("The backup is corrupt: too many files.")
        val names = HashSet<String>()
        val files = (0 until filesArr.length()).map { i ->
            val o = filesArr.optJSONObject(i) ?: throw BackupException("The backup is corrupt: file entry $i is malformed.")
            val name = o.opt("name") as? String
            if (name == null || !FILE_NAME.matches(name) || name == "." || name == "..") {
                throw BackupException("The backup is corrupt: file entry $i has an invalid name.")
            }
            if (!names.add(name)) throw BackupException("The backup is corrupt: duplicate file \"$name\".")
            val data = o.opt("data") as? String ?: throw BackupException("The backup is corrupt: file \"$name\" has no data.")
            val bytes = try {
                Base64.getDecoder().decode(data)
            } catch (e: IllegalArgumentException) {
                throw BackupException("The backup is corrupt: file \"$name\" is not valid base64.", e)
            }
            if (bytes.size > MAX_FILE_BYTES) throw BackupException("The backup is corrupt: file \"$name\" is too large.")
            val expected = o.opt("sha256") as? String
            if (expected == null || !expected.equals(sha256Hex(bytes), ignoreCase = true)) {
                throw BackupException("The backup is corrupt: file \"$name\" failed its checksum.")
            }
            BackupFile(name, (o.opt("originalPath") as? String)?.takeIf { it.isNotEmpty() }, bytes)
        }

        return LauncherBackup(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            createdAtMillis = (root.opt("createdAt") as? Number)?.toLong() ?: 0L,
            appVersionName = app?.opt("versionName") as? String,
            appVersionCode = (app?.opt("versionCode") as? Number)?.toLong() ?: 0L,
            sourcePackage = app?.opt("package") as? String,
            prefs = prefs,
            appStats = stats,
            files = files
        )
    }

    private fun decodeValue(key: String, entry: JSONObject): Any {
        val v = entry.opt("v")
        fun bad(): Nothing = throw BackupException("The backup is corrupt: preference \"$key\" has an invalid value.")
        return when (entry.opt("t")) {
            "s" -> v as? String ?: bad()
            "i" -> if (v is Number && isIntegral(v) && v.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else bad()
            "l" -> if (v is Number && isIntegral(v)) v.toLong() else bad()
            "f" -> if (v is Number && v.toDouble().isFinite()) v.toFloat() else bad()
            "b" -> v as? Boolean ?: bad()
            "set" -> {
                val arr = v as? JSONArray ?: bad()
                (0 until arr.length()).mapTo(LinkedHashSet()) { arr.opt(it) as? String ?: bad() }
            }
            else -> throw BackupException("The backup is corrupt: preference \"$key\" has an unknown type.")
        }
    }

    private fun isIntegral(n: Number): Boolean = when (n) {
        is Int, is Long, is Short, is Byte, is BigInteger -> true
        is BigDecimal -> n.stripTrailingZeros().scale() <= 0
        else -> n.toDouble().let { it.isFinite() && it == Math.floor(it) }
    }

    /**
     * Schema 1 → 2. The legacy file only held the settings prefs, written as a typed entry list,
     * and stored unset strings as "" — dropping those restores "unset", which every reader already
     * treats "" as. No dock or database section, so a restore leaves both untouched, exactly as the
     * old import did.
     */
    private fun migrateV1ToV2(v1: JSONObject): JSONObject {
        val entries = v1.optJSONArray("entries") ?: throw BackupException("The backup is corrupt: no entries.")
        val settings = JSONObject()
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: throw BackupException("The backup is corrupt: entry $i is malformed.")
            val k = o.opt("k") as? String ?: throw BackupException("The backup is corrupt: entry $i has no key.")
            val t = o.opt("t") as? String ?: throw BackupException("The backup is corrupt: entry $i has no type.")
            val v = o.opt("v")
            if (t == "s" && v == "") continue
            settings.put(k, JSONObject().put("t", t).put("v", v ?: JSONObject.NULL))
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", 2)
            put("createdAt", 0L)
            put("prefs", JSONObject().put(SETTINGS_PREFS, settings))
            put("files", JSONArray())
        }
    }

    // --- Helpers over the settings map, kept here so they're covered by the JVM tests ---

    private const val INTENT_SHORTCUTS_PREFIX = "pageIntentShortcuts_"

    /** Absolute icon paths referenced by pinned intent shortcuts on any page or the dock. */
    fun referencedIconPaths(settings: Map<String, Any>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in settings) {
            if (!k.startsWith(INTENT_SHORTCUTS_PREFIX) || v !is String) continue
            val arr = try { JSONArray(v) } catch (_: JSONException) { continue }
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("iconFile", "")?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    /** Rewrites intent-shortcut icon paths through [pathMap] (old absolute path → new absolute path). */
    fun relocateIconPaths(settings: Map<String, Any>, pathMap: Map<String, String>): Map<String, Any> {
        if (pathMap.isEmpty()) return settings
        val out = LinkedHashMap(settings)
        for ((k, v) in settings) {
            if (!k.startsWith(INTENT_SHORTCUTS_PREFIX) || v !is String) continue
            val arr = try { JSONArray(v) } catch (_: JSONException) { continue }
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val newPath = pathMap[o.optString("iconFile", "")] ?: continue
                o.put("iconFile", newPath)
                changed = true
            }
            if (changed) out[k] = arr.toString()
        }
        return out
    }

    /** Package names of every app the backup places somewhere (pages, dock, hidden list, stats). */
    fun referencedPackages(backup: LauncherBackup): Set<String> {
        val components = LinkedHashSet<String>()
        val settings = backup.prefs[SETTINGS_PREFS].orEmpty()
        for ((k, v) in settings) {
            when {
                (k.startsWith("pageApps_") || k == "hiddenApps") && v is Set<*> -> v.forEach { components += it.toString() }
                (k == "favoriteOrder" || k.startsWith("pageAppOrder_")) && v is String -> components += jsonStrings(v)
            }
        }
        val dock = backup.prefs[DOCK_PREFS].orEmpty()
        (dock["dock_unified_order"] as? String)?.let { order ->
            jsonStrings(order).filter { it.startsWith("app:") }.forEach { components += it.removePrefix("app:") }
        }
        (dock["dock_apps"] as? String)?.split("|")?.filter { it.isNotBlank() }?.forEach { components += it }
        backup.appStats?.filter { it.isFavorite }?.forEach { components += it.componentName }
        return components.mapNotNullTo(LinkedHashSet()) { cn -> cn.substringBefore('/').takeIf { it.isNotBlank() && '/' in cn } }
    }

    private fun jsonStrings(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
    } catch (_: JSONException) { emptyList() }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
