package com.meowgi.launcher710.backup

import android.content.Context
import android.database.Cursor
import com.meowgi.launcher710.model.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Every XML file in `shared_prefs` of the app, through the SharedPreferences API so live instances see the change. */
class SharedPrefsStore(private val context: Context) : PrefsStore {

    override fun fileNames(): List<String> =
        File(context.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?: emptyList()

    override fun read(name: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            when (v) {
                is String, is Int, is Long, is Float, is Boolean -> out[k] = v
                is Set<*> -> out[k] = v.filterIsInstance<String>().toSet()
            }
        }
        return out
    }

    /** One commit(): written to a temp file and renamed by the framework, so the file is all-old or all-new. */
    override fun write(name: String, values: Map<String, Any>) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for ((k, v) in values) {
            when (v) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        if (!e.commit()) throw IOException("Couldn't write preferences \"$name\"")
    }
}

/** Every user table of the Room database, read and replaced generically via sqlite_master / PRAGMA. Not for the main thread. */
class RoomDbStore(context: Context) : DbStore {
    private val room = AppDatabase.get(context)
    private val db get() = room.openHelper.writableDatabase

    override fun tableNames(): List<String> =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table')"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    override fun columns(table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val i = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(i)) }
        }

    override fun read(table: String): TableData {
        val columns = columns(table)
        val sql = "SELECT ${columns.joinToString { "`$it`" }} FROM `$table`"
        val rows = db.query(sql).use { c ->
            buildList {
                while (c.moveToNext()) add(columns.indices.map { cell(c, it) })
            }
        }
        return TableData(columns, rows)
    }

    private fun cell(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> c.getString(i)
        Cursor.FIELD_TYPE_BLOB -> Blob(c.getBlob(i))
        else -> null
    }

    override fun replaceAll(table: String, data: TableData) {
        room.runInTransaction(Runnable {
            val w = db
            w.execSQL("DELETE FROM `$table`")
            if (data.rows.isEmpty()) return@Runnable
            val stmt = w.compileStatement(
                "INSERT INTO `$table` (${data.columns.joinToString { "`$it`" }}) VALUES (${data.columns.joinToString { "?" }})"
            )
            for (row in data.rows) {
                stmt.clearBindings()
                row.forEachIndexed { i, v ->
                    val at = i + 1
                    when (v) {
                        null -> stmt.bindNull(at)
                        is Long -> stmt.bindLong(at, v)
                        is Double -> stmt.bindDouble(at, v)
                        is String -> stmt.bindString(at, v)
                        is Blob -> stmt.bindBlob(at, v.bytes)
                        else -> throw IllegalArgumentException("Unsupported value in $table")
                    }
                }
                stmt.executeInsert()
            }
            stmt.close()
        })
    }
}

/** Restored files live in `files/backup_assets/<restore id>/`; they're written to `files/backup_staging/` first. */
class FileAssetStore(context: Context) : AssetStore {
    private val staging = File(context.filesDir, "backup_staging")
    private val live = File(context.filesDir, "backup_assets")

    override fun read(path: String): ByteArray? {
        val f = File(path)
        return if (f.isFile && f.length() <= BackupCodec.MAX_ASSET_BYTES) f.readBytes() else null
    }

    override fun stage(id: String, assets: List<BackupAsset>): Map<String, String> {
        val dir = File(staging, id)
        dir.deleteRecursively()
        if (assets.isEmpty()) return emptyMap()
        if (!dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
        return assets.associate { a ->
            FileOutputStream(File(dir, a.name)).use { out ->
                out.write(a.bytes)
                out.fd.sync()
            }
            a.name to File(File(live, id), a.name).absolutePath
        }
    }

    override fun commit(id: String) {
        val from = File(staging, id)
        if (!from.exists()) return
        live.mkdirs()
        if (!from.renameTo(File(live, id))) throw IOException("Couldn't move restored files into place")
    }

    override fun discard(id: String) {
        File(staging, id).deleteRecursively()
        File(live, id).deleteRecursively()
    }
}

/** `files/backup_journal/<id>.zip` exists exactly while a restore is being applied. */
class FileJournal(context: Context) : Journal {
    private val dir = File(context.filesDir, "backup_journal")

    override fun begin(id: String, snapshot: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(snapshot)
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, "$id.zip"))) {
            tmp.delete()
            throw IOException("Couldn't write the restore journal")
        }
    }

    override fun pending(): Pair<String, ByteArray>? {
        val f = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".zip") } ?: return null
        return f.name.removeSuffix(".zip") to f.readBytes()
    }

    override fun finish() {
        dir.deleteRecursively()
    }

    companion object {
        fun hasPending(context: Context): Boolean =
            File(context.filesDir, "backup_journal").listFiles()?.any { it.name.endsWith(".zip") } == true
    }
}
