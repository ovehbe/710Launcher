package com.meowgi.launcher710.backup

/** Every user-facing failure; [message] is shown as-is. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** SQLite blob cell; wraps the bytes so rows compare by content. */
class Blob(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is Blob && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Blob(${bytes.size})"
}

/** One table. Cells are null, Long, Double, String or [Blob]. */
data class TableData(val columns: List<String>, val rows: List<List<Any?>>)

/** A file the prefs point at by absolute path (e.g. a pinned shortcut's icon). */
class BackupAsset(val name: String, val originalPath: String, val bytes: ByteArray) {
    override fun equals(other: Any?) =
        other is BackupAsset && name == other.name && originalPath == other.originalPath && bytes.contentEquals(other.bytes)
    override fun hashCode() = (name.hashCode() * 31 + originalPath.hashCode()) * 31 + bytes.contentHashCode()
}

data class BackupSource(val packageName: String?, val versionName: String?, val versionCode: Long)

/**
 * Decoded backup. Pref values are String, Int, Long, Float, Boolean or Set<String>.
 * A restore replaces exactly the prefs files in [prefs] and the tables in [tables]; anything
 * not listed is left alone (a migrated settings-only legacy file therefore keeps the dock and DB).
 */
data class BackupPayload(
    val createdAtMillis: Long,
    val source: BackupSource,
    val prefs: Map<String, Map<String, Any>>,
    val tables: Map<String, TableData>,
    val assets: List<BackupAsset>,
    /** Schema the file was written with, before migration. */
    val originalSchemaVersion: Int = BackupCodec.CURRENT_SCHEMA_VERSION
) {
    val prefKeyCount: Int get() = prefs.values.sumOf { it.size }
    val rowCount: Int get() = tables.values.sumOf { it.rows.size }
}

data class RestoreReport(
    val prefFiles: Int,
    val prefKeys: Int,
    val tables: Int,
    val rows: Int,
    val assets: Int,
    /** Tables in the backup that this app version doesn't have. */
    val skippedTables: List<String>,
    /** "table.column" in the backup that this app version doesn't have. */
    val droppedColumns: List<String>,
    /** Packages the backup places somewhere that aren't installed here; their entries stay dormant. */
    val missingPackages: List<String> = emptyList()
)
