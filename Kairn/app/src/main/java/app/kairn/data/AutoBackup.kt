package app.kairn.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a backup every [AppSettings.autoBackupDays] days into a SAF folder picked once, pruning our own zips to
 * [AppSettings.autoBackupKeep]. Only [PREFIX] files are touched, and failure is silent: a scheduled backup must
 * never interrupt reading with a dialog.
 */
class AutoBackup(private val ctx: Context, private val store: Store, private val backup: Backup) {

    /** Runs one if it is due; returns true when a zip was written. Cheap when off. */
    suspend fun runIfDue(): Boolean = withContext(Dispatchers.IO) {
        val s = store.settings.value
        val tree = s.autoBackupFolder.takeIf { it.isNotBlank() } ?: return@withContext false
        val every = s.autoBackupDays.coerceAtLeast(1) * DAY_MS
        if (s.lastAutoBackup > 0 && System.currentTimeMillis() - s.lastAutoBackup < every) return@withContext false

        runCatching {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: error("folder is gone")
            if (!dir.canWrite()) error("folder is not writable")
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
            val doc = dir.createFile(ZIP, name) ?: error("cannot create $name")
            backup.write(doc.uri, includeBookFiles = false).getOrThrow()
            prune(dir, s.autoBackupKeep)
            store.saveSettings { it.copy(lastAutoBackup = System.currentTimeMillis(), lastBackup = System.currentTimeMillis(), lastBackupName = name) }
            true
        }.getOrDefault(false)
    }

    /** Keeps the newest [keep] of our own zips. Names sort by date. */
    private fun prune(dir: DocumentFile, keep: Int) {
        dir.listFiles()
            .filter { it.isFile && it.name.orEmpty().startsWith(PREFIX) }
            .sortedByDescending { it.name.orEmpty() }
            .drop(keep.coerceAtLeast(1))
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        const val PREFIX = "kairn-auto-"
        private const val ZIP = "application/zip"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
