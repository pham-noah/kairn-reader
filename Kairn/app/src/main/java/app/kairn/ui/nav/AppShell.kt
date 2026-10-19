package app.kairn.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.kairn.KairnApp
import app.kairn.data.Book
import app.kairn.ui.kit.*
import app.kairn.ui.screens.*
import app.kairn.ui.theme.K
import app.kairn.ui.theme.ambientWash
import app.kairn.ui.theme.glass
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen screens that sit above the tabs. */
enum class Overlay { Onboarding, Add, Backup }

/**
 * App shell: three tabs, overlays, one toast host, and the system pickers.
 * Screens never talk to Android APIs themselves; they get callbacks from here.
 */
@Composable
fun AppShell(app: KairnApp, openBook: (Book) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = remember { ToastState() }
    val settings by app.store.settings.collectAsState()

    var tab by remember { mutableStateOf(Tab.Library) }
    var overlay by remember { mutableStateOf<Overlay?>(if (!app.store.settings.value.onboarded) Overlay.Onboarding else null) }
    var shelf by remember { mutableStateOf<String?>(null) }
    val backupUi = remember { BackupUi() }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { uris.forEach { app.library.importPicked(it) }; toast.show("Added ${uris.size} ${if (uris.size == 1) "book" else "books"}") }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { app.library.addFolder(u); scope.launch { app.library.scan(); toast.show("Watching this folder") } }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) scope.launch {
            backupUi.phase = BackupPhase.Running; backupUi.progress = 0f
            app.backup.write(u, backupUi.includeFiles) { backupUi.progress = it }.fold(
                { n ->
                    val name = DocumentFile.fromSingleUri(ctx, u)?.name ?: "kairn-backup.zip"
                    app.store.saveSettings { it.copy(lastBackup = System.currentTimeMillis(), lastBackupName = name) }
                    backupUi.fileName = name; backupUi.detail = "$n files saved"; backupUi.phase = BackupPhase.Done
                },
                { e -> backupUi.detail = e.message ?: "Something went wrong."; backupUi.phase = BackupPhase.Failed },
            )
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) restoreUri = u }

    val launchPickBook = { pickBook.launch(arrayOf("application/epub+zip")) }
    // L-01: open the picker at the device storage root so "find books" sweeps everything at once.
    val launchPickFolder = { pickFolder.launch(Uri.parse("content://com.android.externalstorage.documents/root/primary")) }

    BackHandler(overlay != null && overlay != Overlay.Onboarding) { overlay = null }

    CompositionLocalProvider(LocalToast provides toast) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.ambientWash() else Modifier.background(K.bg))) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (overlay) {
                        Overlay.Onboarding -> OnboardingScreen(app.store) { goAdd ->
                            app.store.saveSettings { it.copy(onboarded = true) }; overlay = if (goAdd) Overlay.Add else null
                        }
                        Overlay.Add -> AddBooksScreen(app.store, { overlay = null }, launchPickFolder, launchPickBook,
                            onImportFile = { file, name ->
                                scope.launch {
                                    val b = app.library.addBook(Uri.fromFile(file), null, name.removeSuffix(".epub"))
                                    toast.show(if (b.broken) "Could not read $name" else "Added ${b.title}")
                                }
                            })
                        Overlay.Backup -> BackupScreen(backupUi, app.store, { overlay = null },
                            onBackupNow = {
                                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                createBackup.launch("kairn-backup-$stamp.zip")
                            },
                            onRestoreFromFile = { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) })
                        null -> when (tab) {
                            Tab.Library -> LibraryScreen(app.store, shelf, { shelf = null }, openBook, { overlay = Overlay.Add }, launchPickFolder, launchPickBook)
                            Tab.Collections -> CollectionsScreen(app.store) { shelf = it; tab = Tab.Library }
                            Tab.Settings -> SettingsScreen(app.store, app.library, { overlay = Overlay.Add }, { overlay = Overlay.Backup }, { scope.launch { app.library.scan(); toast.show("Looking for books…") } })
                        }
                    }
                }
                if (overlay == null) KNavBar(tab, { tab = it })
            }
            toast.Host(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

            restoreUri?.let { u ->
                RestoreSheet(DocumentFile.fromSingleUri(ctx, u)?.name ?: "backup.zip", { restoreUri = null }) { replace ->
                    restoreUri = null
                    scope.launch {
                        app.backup.restore(u, replace).fold(
                            { toast.show(if (replace) "Restored. A safety copy of your old data was kept." else "Merged. A safety copy of your old data was kept.") },
                            // The exception message matters: "nothing was changed" and "restored part-way" are
                            // different outcomes that need different messages.
                            { e -> toast.show(e.message ?: "Restore failed. Your data is unchanged.") },
                        )
                    }
                }
            }
        }
    }
}
