package com.meowgi.launcher710.backup

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream

/** Android side of backup & restore. Everything here blocks; call it off the main thread. */
class BackupManager(context: Context) {
    private val app = context.applicationContext

    private fun engine() = BackupEngine(
        prefs = SharedPrefsStore(app),
        db = RoomDbStore(app),
        assets = FileAssetStore(app),
        journal = FileJournal(app),
        dataRoots = dataRoots(app),
        source = source()
    )

    /** A backup that has been read and fully validated, plus what this device lacks for it. */
    class Prepared(val payload: BackupPayload, val missingPackages: List<String>)

    /** Writes a backup to [uri]; the encoded bytes are decoded again first so a bad file is never written. */
    fun export(uri: Uri): BackupPayload {
        val payload = engine().capture()
        val bytes = BackupCodec.encode(payload)
        BackupCodec.decode(bytes)
        val out = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("Couldn't open the chosen file for writing.")
        out.use { it.write(bytes) }
        return payload
    }

    fun read(uri: Uri): Prepared {
        val bytes = try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > BackupCodec.MAX_FILE_BYTES) {
                        throw BackupException("The file is too large to be a 710 Launcher backup.")
                    }
                }
                out.toByteArray()
            } ?: throw BackupException("Couldn't open the chosen file.")
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Couldn't read the chosen file.", e)
        }
        val payload = BackupCodec.decode(bytes)
        return Prepared(payload, missingPackages(payload))
    }

    fun restore(prepared: Prepared): RestoreReport =
        engine().restore(prepared.payload).copy(missingPackages = prepared.missingPackages)

    private fun missingPackages(payload: BackupPayload): List<String> {
        val pm = app.packageManager
        return BackupScan.referencedPackages(payload, app.packageName).filter { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                false
            } catch (e: PackageManager.NameNotFoundException) {
                true
            }
        }.sorted()
    }

    private fun source(): BackupSource {
        val info = try {
            @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val code = if (info == null) 0L else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        return BackupSource(app.packageName, info?.versionName, code)
    }

    companion object {
        private const val TAG = "BackupManager"

        /** The app's data dir under each name it goes by, so stored absolute paths are recognised. */
        fun dataRoots(context: Context): List<String> = listOfNotNull(
            context.dataDir.absolutePath,
            context.filesDir.parentFile?.absolutePath,
            "/data/data/${context.packageName}",
            "/data/user/0/${context.packageName}"
        ).distinct()

        /**
         * Undoes a restore that was interrupted (process killed mid-apply). Cheap when there is
         * nothing to do; otherwise blocks the caller until the previous state is back, since the
         * UI must not read half-restored prefs.
         */
        fun recoverInterruptedRestore(context: Context) {
            if (!FileJournal.hasPending(context)) return
            val app = context.applicationContext
            val t = Thread {
                try {
                    if (BackupManager(app).engine().recoverInterrupted()) Log.w(TAG, "Rolled back an interrupted restore")
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't roll back an interrupted restore", e)
                }
            }
            t.start()
            t.join()
        }
    }
}
