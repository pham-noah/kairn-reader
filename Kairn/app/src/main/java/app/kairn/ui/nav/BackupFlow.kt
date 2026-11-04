package app.kairn.ui.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.kairn.R
import app.kairn.data.AutoBackup
import app.kairn.data.Backup
import app.kairn.data.Library
import app.kairn.data.Store
import app.kairn.ui.kit.ToastState
import app.kairn.ui.screens.BackupPhase
import app.kairn.ui.screens.BackupUi
import app.kairn.ui.screens.RestoreSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DS-01: the three document contracts the Backup screen launches - create a zip, open one to restore, choose
 * the folder automatic backups go to - and the state they write into.
 *
 * They lived in [AppShell], which is meant to decide what is on screen; how a zip is written, how a restore is
 * confirmed and how a SAF grant is taken are not shell decisions. The launchers have to be registered from
 * composition, so [rememberBackupFlow] does that and hands the flow its three entry points.
 */
@Stable
internal class BackupFlow(
    private val ctx: Context,
    private val store: Store,
    private val library: Library,
    private val backup: Backup,
    private val autoBackup: AutoBackup,
    private val toast: ToastState,
    private val scope: CoroutineScope,
) {
    /** Progress and phase outlive the screen: backing out and returning must not lose a running backup. */
    val ui = BackupUi()

    private var restoreUri by mutableStateOf<Uri?>(null)

    private var launchCreate: ((String) -> Unit)? = null
    private var launchOpen: (() -> Unit)? = null
    private var launchPickFolder: (() -> Unit)? = null

    /** The folder automatic backups land in, by the name the picker showed, or null when it is off. */
    fun autoFolderName(): String? = store.settings.value.autoBackupFolder
        .takeIf { it.isNotBlank() }
        ?.let { u -> runCatching { DocumentFile.fromTreeUri(ctx, u.toUri())?.name }.getOrNull() }

    fun backUpNow() {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        launchCreate?.invoke("kairn-backup-$stamp.zip")
    }

    fun restoreFromFile() = launchOpen?.invoke()

    fun pickAutoFolder() = launchPickFolder?.invoke()

    internal fun attachLaunchers(create: (String) -> Unit, open: () -> Unit, pickFolder: () -> Unit) {
        launchCreate = create
        launchOpen = open
        launchPickFolder = pickFolder
    }

    internal fun onCreateResult(uri: Uri) {
        scope.launch {
            ui.phase = BackupPhase.Running; ui.progress = 0f
            backup.write(uri, ui.includeFiles) { ui.progress = it }.fold(
                { n ->
                    val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "kairn-backup.zip"
                    store.saveSettings { it.copy(lastBackup = System.currentTimeMillis(), lastBackupName = name) }
                    ui.fileName = name
                    ui.detail = ctx.resources.getQuantityString(R.plurals.files_saved, n, n)
                    ui.phase = BackupPhase.Done
                },
                { e -> ui.detail = e.message ?: ctx.getString(R.string.toast_something_wrong); ui.phase = BackupPhase.Failed },
            )
        }
    }

    internal fun onRestorePicked(uri: Uri) { restoreUri = uri }

    /** The automatic-backup folder: granted once here, persisted, and written by data/AutoBackup with no
     * further prompts. Turning the switch off only forgets the folder - the zips already written stay. */
    internal fun onAutoFolderPicked(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        store.saveSettings { it.copy(autoBackupFolder = uri.toString()) }
        scope.launch { if (autoBackup.runIfDue()) toast.show(ctx.getString(R.string.s_backup_saved)) }
    }

    /** The "replace or merge?" confirmation, and the restore itself, while a file is waiting to be restored. */
    @Composable
    fun RestoreConfirmation() {
        val uri = restoreUri ?: return
        RestoreSheet(DocumentFile.fromSingleUri(ctx, uri)?.name ?: "backup.zip", { restoreUri = null }) { replace ->
            restoreUri = null
            scope.launch {
                backup.restore(uri, replace).fold(
                    {
                        toast.show(ctx.getString(if (replace) R.string.toast_restored else R.string.toast_merged))
                        // The records are back but their files may not be (a backup without book files):
                        // scan straight away so the reader is not left with missing books.
                        scope.launch { library.scan(force = true) }
                    },
                    // The message matters: "nothing was changed" and "restored part-way" are different
                    // outcomes that need different wording.
                    { e -> toast.show(e.message ?: ctx.getString(R.string.toast_restore_failed)) },
                )
            }
        }
    }
}

@Composable
internal fun rememberBackupFlow(
    store: Store,
    library: Library,
    backup: Backup,
    autoBackup: AutoBackup,
    toast: ToastState,
    scope: CoroutineScope,
): BackupFlow {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val flow = remember { BackupFlow(ctx, store, library, backup, autoBackup, toast, scope) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) flow.onCreateResult(u)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) flow.onRestorePicked(u)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) flow.onAutoFolderPicked(u)
    }
    flow.attachLaunchers(
        create = { name -> create.launch(name) },
        open = { open.launch(arrayOf("application/zip", "application/octet-stream")) },
        pickFolder = { pickFolder.launch(null) },
    )
    return flow
}
