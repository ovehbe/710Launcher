package com.meowgi.launcher710.backup

/** All SharedPreferences files of the app. [write] replaces one file's contents atomically. */
interface PrefsStore {
    fun fileNames(): List<String>
    fun read(name: String): Map<String, Any>
    fun write(name: String, values: Map<String, Any>)
}

/** The app database. [replaceAll] swaps a table's rows in one transaction. */
interface DbStore {
    fun tableNames(): List<String>
    fun columns(table: String): List<String>
    fun read(table: String): TableData
    fun replaceAll(table: String, data: TableData)
}

/**
 * Files referenced by the prefs. [stage] writes a restore's assets somewhere invisible and returns
 * name → final absolute path; [commit] makes them live in one rename; [discard] removes either.
 */
interface AssetStore {
    fun read(path: String): ByteArray?
    fun stage(id: String, assets: List<BackupAsset>): Map<String, String>
    fun commit(id: String)
    fun discard(id: String)
}

/**
 * Durable marker that a restore is being applied, holding an encoded snapshot of what it overwrites.
 * [begin] must be atomic (write then rename).
 */
interface Journal {
    fun begin(id: String, snapshot: ByteArray)
    fun pending(): Pair<String, ByteArray>?
    fun finish()
}

/**
 * Captures and applies backups against abstract stores, so the whole flow (including failure
 * mid-apply) is unit-tested on the JVM. Coverage is generic: every prefs file and every table the
 * stores report, plus every file under [dataRoots] that a pref value points at.
 */
class BackupEngine(
    private val prefs: PrefsStore,
    private val db: DbStore,
    private val assets: AssetStore,
    private val journal: Journal,
    private val dataRoots: List<String>,
    private val source: BackupSource,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /**
         * Columns this build has that older backups may lack: seeded from another column of the
         * same row instead of the SQL default. Entries for columns the DB doesn't have are ignored.
         */
        val COLUMN_FILLS: Map<String, Map<String, String>> = mapOf(
            "app_stats" to mapOf("frecency" to "launchCount", "frecencyAt" to "lastLaunched")
        )
    }

    fun capture(): BackupPayload {
        val prefData = prefs.fileNames().filter(BackupCodec::isValidName).sorted().associateWith { prefs.read(it) }
        val tables = db.tableNames().sorted().associateWith { db.read(it) }
        val used = HashSet<String>()
        val files = BackupScan.referencedPaths(prefData, dataRoots).mapNotNull { path ->
            val bytes = assets.read(path) ?: return@mapNotNull null
            if (bytes.size > BackupCodec.MAX_ASSET_BYTES) return@mapNotNull null
            BackupAsset(uniqueName(path.substringAfterLast('/'), used), path, bytes)
        }
        return BackupPayload(clock(), source, prefData, tables, files)
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "_").trimStart('.', '_', '-')
            .ifEmpty { "file" }.take(100)
        var i = 0
        while (true) {
            val name = "${i}_$clean"
            if (used.add(name)) return name
            i++
        }
    }

    private class TablePlan(val table: String, val data: TableData)

    /**
     * Applies [payload]. Everything that can fail on bad input is checked before the first write;
     * if a write still fails, every touched prefs file and table is put back from the snapshot and
     * the error is rethrown. A crash mid-apply is undone by [recoverInterrupted] on the next start.
     */
    fun restore(payload: BackupPayload): RestoreReport {
        val existing = db.tableNames().toSet()
        val skipped = ArrayList<String>()
        val dropped = ArrayList<String>()
        val plans = ArrayList<TablePlan>()
        for ((table, data) in payload.tables) {
            if (table !in existing) { skipped += table; continue }
            plans += TablePlan(table, adapt(table, data, db.columns(table), dropped))
        }

        val id = "r${clock()}"
        val finalPaths = try {
            assets.stage(id, payload.assets)
        } catch (e: Exception) {
            assets.discard(id)
            throw BackupException("Couldn't prepare the backup's files. Nothing was changed.", e)
        }
        val relocation = payload.assets.mapNotNull { a -> finalPaths[a.name]?.let { a.originalPath to it } }.toMap()
        val newPrefs = payload.prefs.mapValues { (_, v) -> BackupScan.relocate(v, relocation) }

        val snapshot = try {
            BackupPayload(
                createdAtMillis = clock(),
                source = source,
                prefs = newPrefs.keys.associateWith { prefs.read(it) },
                tables = plans.associate { it.table to db.read(it.table) },
                assets = emptyList()
            )
        } catch (e: Exception) {
            assets.discard(id)
            throw BackupException("Couldn't read the current settings. Nothing was changed.", e)
        }
        try {
            journal.begin(id, BackupCodec.encode(snapshot))
        } catch (e: Exception) {
            assets.discard(id)
            throw BackupException("Couldn't save a safety copy of the current settings. Nothing was changed.", e)
        }

        // A failed replaceAll / write leaves its own table or file as it was, so only completed ones are undone.
        val doneTables = ArrayList<String>()
        val donePrefs = ArrayList<String>()
        try {
            assets.commit(id)
            for (p in plans) { db.replaceAll(p.table, p.data); doneTables += p.table }
            for ((name, values) in newPrefs) { prefs.write(name, values); donePrefs += name }
        } catch (e: Exception) {
            val restored = rollBack(
                snapshot.copy(tables = snapshot.tables.filterKeys { it in doneTables }, prefs = snapshot.prefs.filterKeys { it in donePrefs }),
                id
            )
            if (restored) journal.finish()
            throw BackupException(
                if (restored) "Restoring failed (${e.message ?: e.javaClass.simpleName}). Nothing was changed."
                else "Restoring failed and the previous settings couldn't be put back yet; they will be on the next start.",
                e
            )
        }
        journal.finish()
        return RestoreReport(
            prefFiles = newPrefs.size,
            prefKeys = newPrefs.values.sumOf { it.size },
            tables = plans.size,
            rows = plans.sumOf { it.data.rows.size },
            assets = finalPaths.size,
            skippedTables = skipped,
            droppedColumns = dropped
        )
    }

    /** Projects a backed-up table onto the current schema: unknown columns dropped, [COLUMN_FILLS] seeded. */
    private fun adapt(table: String, data: TableData, current: List<String>, dropped: MutableList<String>): TableData {
        val keep = data.columns.filter { it in current }
        data.columns.filter { it !in current }.forEach { dropped += "$table.$it" }
        val fills = (COLUMN_FILLS[table] ?: emptyMap()).filter { (col, from) ->
            col in current && col !in data.columns && from in data.columns
        }
        val idx = data.columns.withIndex().associate { it.value to it.index }
        val columns = keep + fills.keys
        val rows = data.rows.map { row -> keep.map { row[idx.getValue(it)] } + fills.values.map { row[idx.getValue(it)] } }
        return TableData(columns, rows)
    }

    /** Puts back every prefs file and table in [snapshot]; true when all of it succeeded. */
    private fun rollBack(snapshot: BackupPayload, id: String): Boolean {
        var ok = true
        for ((table, data) in snapshot.tables) {
            try { db.replaceAll(table, data) } catch (e: Exception) { ok = false }
        }
        for ((name, values) in snapshot.prefs) {
            try { prefs.write(name, values) } catch (e: Exception) { ok = false }
        }
        try { assets.discard(id) } catch (_: Exception) { }
        return ok
    }

    /** Call on startup: if a restore was interrupted, put the pre-restore state back. True if it did. */
    fun recoverInterrupted(): Boolean {
        val (id, bytes) = journal.pending() ?: return false
        val snapshot = try {
            BackupCodec.decode(bytes)
        } catch (e: BackupException) {
            journal.finish()
            return false
        }
        if (rollBack(snapshot, id)) journal.finish()
        return true
    }
}
