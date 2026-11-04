package app.kairn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a backup every [AppSettings.autoBackupDays] days into a SAF folder picked once, pruning our own zips to
 * [AppSettings.autoBackupKeep]. Only [PREFIX] files are touched, and failure is silent: a scheduled backup must
 * never interrupt reading with a dialog.
 *
 * The folder and the writer arrive as functions instead of a Context and a Backup, and both speak [DocHandle]
 * rather than `Uri` (a stub in JVM tests), so the whole path - create, write, prune, record - runs in
 * [AutoBackupTest] without a device. Production passes [SafBackupFolder] and `Backup::write`.
 */
class AutoBackup(
    private val store: Store,
    private val openFolder: (String) -> BackupFolder?,
    private val write: suspend (DocHandle) -> Result<Int>,
) {

    /** Runs one if it is due; returns true when a zip was written. Cheap when off. */
    suspend fun runIfDue(): Boolean = withContext(Dispatchers.IO) {
        val s = store.settings.value
        val tree = s.autoBackupFolder.takeIf { it.isNotBlank() } ?: return@withContext false
        if (!autoBackupIsDue(s.lastAutoBackup, System.currentTimeMillis(), s.autoBackupDays)) return@withContext false

        runCatching {
            val dir = openFolder(tree) ?: error("folder is gone")
            if (!dir.canWrite()) error("folder is not writable")
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
            val doc = dir.create(name, ZIP) ?: error("cannot create $name")
            write(doc.handle).getOrThrow()
            prune(dir, s.autoBackupKeep)
            store.saveSettings { it.copy(lastAutoBackup = System.currentTimeMillis(), lastBackup = System.currentTimeMillis(), lastBackupName = name) }
            true
        }.getOrDefault(false)
    }

    /** Keeps the newest [keep] of our own zips. Names sort by date. */
    private fun prune(dir: BackupFolder, keep: Int) {
        val files = dir.list().filter { it.isFile }
        val stale = staleBackups(files.mapNotNull { it.name }, keep).toSet()
        files.filter { it.name in stale }.forEach { dir.delete(it.handle) }
    }

    companion object {
        const val PREFIX = "kairn-auto-"
        private const val ZIP = "application/zip"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** Due when never written, or when the interval has passed. One day is the floor. */
internal fun autoBackupIsDue(lastAutoBackup: Long, now: Long, days: Int, dayMs: Long = 86_400_000L) =
    lastAutoBackup == 0L || now - lastAutoBackup >= days.coerceAtLeast(1) * dayMs

/** Names of OUR backups to delete: everything but the newest [keep]. Names sort by date. */
internal fun staleBackups(names: List<String>, keep: Int, prefix: String = AutoBackup.PREFIX) =
    names.filter { it.startsWith(prefix) }.sortedDescending().drop(keep.coerceAtLeast(1))
