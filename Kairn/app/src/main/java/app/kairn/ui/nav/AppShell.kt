package app.kairn.ui.nav

import app.kairn.ui.ReaderActivity

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.kairn.KairnApp
import app.kairn.R
import app.kairn.data.Book
import app.kairn.data.ClippingImport
import app.kairn.data.Clippings
import app.kairn.data.Update
import app.kairn.ui.kit.*
import app.kairn.ui.screens.*
import app.kairn.ui.theme.K
import app.kairn.ui.theme.ambientWash
import app.kairn.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Decided after the store's first read, never from settings.value here: on a cold start that read has
    // not finished yet, so it still holds the defaults and every returning reader would get onboarding.
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var shelf by remember { mutableStateOf<String?>(null) }
    val backupUi = remember { BackupUi() }
    // Recomputed when the tab composes, and after clearing: the number is a hint, not a live counter.
    var cacheStats by remember { mutableStateOf(app.searchIndex.stats()) }
    val cacheLabel = remember(cacheStats) {
        val (n, bytes) = cacheStats
        if (n == 0) "" else ctx.resources.getQuantityString(R.plurals.search_cache_size, n, n, android.text.format.Formatter.formatShortFileSize(ctx, bytes))
    }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val updateUi = remember { UpdateUi() }
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    // DS-04: a store build (F-Droid) cannot update itself, so the whole check disappears there.
    val selfUpdate = remember { ctx.resources.getBoolean(R.bool.self_update) }
    val clipper = remember { ClippingImport(ctx, app.store, app.readium) }
    var importingClippings by remember { mutableStateOf(false) }

    /**
     * DS-04. One request, at most once a day, only while the switch is on. [openSheet] is false for the
     * automatic check on start: a new release is offered as a toast with an action, never as a sheet over
     * the library the reader just opened.
     */
    fun checkUpdates(openSheet: Boolean) {
        if (!selfUpdate || updateUi.checking) return
        updateUi.checking = true
        updateUi.message = null
        if (openSheet) { updateUi.release = null; updateUi.apk = null; updateUi.open = true }
        scope.launch {
            when (val c = Update.look(versionName)) {
                is Update.Check.Found -> {
                    updateUi.checking = false
                    updateUi.release = c.release
                    if (openSheet) updateUi.open = true
                    else toast.show(
                        ctx.getString(R.string.toast_update_available, c.release.version),
                        ctx.getString(R.string.s_update),
                    ) { updateUi.open = true }
                }
                // "Nothing newer" and "could not ask" are different answers, and only one of them is good news.
                Update.Check.Current -> { updateUi.checking = false; updateUi.message = null }
                Update.Check.Failed -> {
                    updateUi.checking = false
                    updateUi.message = ctx.getString(R.string.s_update_check_failed)
                }
            }
            app.store.saveSettings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        }
    }

    LaunchedEffect(Unit) {
        app.store.awaitLoaded()
        if (!app.store.settings.value.onboarded) overlay = Overlay.Onboarding
        val s = app.store.settings.value
        // After the library is up and a moment later still: the check must never delay the first paint.
        if (selfUpdate && s.checkUpdates && System.currentTimeMillis() - s.lastUpdateCheck > 24L * 60 * 60 * 1000) {
            delay(2500)
            checkUpdates(openSheet = false)
        }
    }
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { uris.forEach { app.library.importPicked(it) }; toast.show(ctx.resources.getQuantityString(R.plurals.books_added, uris.size, uris.size)) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { app.library.addFolder(u); scope.launch { app.library.scan(); toast.show(ctx.getString(R.string.toast_watching_folder)) } }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        if (u != null) scope.launch {
            backupUi.phase = BackupPhase.Running; backupUi.progress = 0f
            app.backup.write(u, backupUi.includeFiles) { backupUi.progress = it }.fold(
                { n ->
                    val name = DocumentFile.fromSingleUri(ctx, u)?.name ?: "kairn-backup.zip"
                    app.store.saveSettings { it.copy(lastBackup = System.currentTimeMillis(), lastBackupName = name) }
                    backupUi.fileName = name
                    backupUi.detail = ctx.resources.getQuantityString(R.plurals.files_saved, n, n)
                    backupUi.phase = BackupPhase.Done
                },
                { e -> backupUi.detail = e.message ?: ctx.getString(R.string.toast_something_wrong); backupUi.phase = BackupPhase.Failed },
            )
        }
    }
    // The automatic-backup folder: granted once here, persisted, and written by data/AutoBackup with no
    // further prompts. Turning the switch off only forgets the folder - the zips already written stay.
    val pickAutoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            app.store.saveSettings { it.copy(autoBackupFolder = u.toString()) }
            scope.launch { if (app.autoBackup.runIfDue()) toast.show(ctx.getString(R.string.s_backup_saved)) }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) restoreUri = u }
    // DS-03: "My Clippings.txt" from a Kindle. text/plain in practice, but some file providers report
    // octet-stream for a .txt on removable storage, so both are accepted.
    val pickClippings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && !importingClippings) {
            importingClippings = true
            scope.launch {
                val text = clipper.readText(u)
                if (text == null) {
                    importingClippings = false
                    toast.show(ctx.getString(R.string.toast_clippings_unreadable))
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) { Clippings.parse(text) }
                if (parsed.isEmpty()) {
                    importingClippings = false
                    toast.show(ctx.getString(R.string.toast_clippings_none))
                    return@launch
                }
                toast.show(ctx.resources.getQuantityString(R.plurals.importing_clippings, parsed.size, parsed.size))
                val r = clipper.run(parsed)
                importingClippings = false
                toast.show(
                    ctx.resources.getQuantityString(R.plurals.clippings_imported, r.imported, r.imported, r.notFound, r.unmatched),
                )
            }
        }
    }

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
                            onImportClippings = { pickClippings.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                            onImportFile = { file, name ->
                                scope.launch {
                                    val b = app.library.addBook(Uri.fromFile(file), null, name.removeSuffix(".epub"))
                                    toast.show(if (b.broken) ctx.getString(R.string.toast_could_not_read, name) else ctx.getString(R.string.toast_added, b.title))
                                }
                            })
                        Overlay.Backup -> BackupScreen(backupUi, app.store, { overlay = null },
                            onBackupNow = {
                                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                createBackup.launch("kairn-backup-$stamp.zip")
                            },
                            onRestoreFromFile = { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                            autoFolderName = app.store.settings.collectAsStateWithLifecycle().value.autoBackupFolder
                                .takeIf { it.isNotBlank() }
                                ?.let { u -> DocumentFile.fromTreeUri(ctx, Uri.parse(u))?.name },
                            onPickAutoFolder = { pickAutoFolder.launch(null) },
                            onAutoBackupOff = { app.store.saveSettings { it.copy(autoBackupFolder = "") } })
                        null -> when (tab) {
                            Tab.Library -> LibraryScreen(app.store, shelf, { shelf = it }, openBook, { overlay = Overlay.Add }, launchPickFolder, launchPickBook,
                                onBackup = { overlay = Overlay.Backup },
                                onOpenAt = { b, locator ->
                                    ctx.startActivity(
                                        android.content.Intent(ctx, ReaderActivity::class.java)
                                            .putExtra(ReaderActivity.EXTRA_ID, b.id)
                                            .putExtra(ReaderActivity.EXTRA_LOCATOR, locator)
                                    )
                                })
                            Tab.Collections -> CollectionsScreen(app.store) { shelf = it; tab = Tab.Library }
                            Tab.Settings -> SettingsScreen(app.store, app.library, { overlay = Overlay.Add }, { overlay = Overlay.Backup }, { scope.launch { app.library.scan(); toast.show(ctx.getString(R.string.toast_looking_for_books)) } },
                                searchCacheLabel = cacheLabel,
                                onClearSearchCache = {
                                    app.searchIndex.clear()
                                    cacheStats = 0 to 0L
                                    toast.show(ctx.getString(R.string.s_search_cache_cleared))
                                },
                                onCheckUpdates = { checkUpdates(openSheet = true) },
                                showUpdates = selfUpdate)
                        }
                    }
                }
                if (overlay == null) KNavBar(tab, { tab = it })
            }
            toast.Host(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

            if (updateUi.open) UpdateSheet(
                updateUi,
                onCheck = { checkUpdates(openSheet = true) },
                onDownload = {
                    val r = updateUi.release
                    if (r != null && !updateUi.downloading) {
                        updateUi.downloading = true
                        updateUi.progress = 0f
                        scope.launch {
                            val f = Update.download(ctx, r) { p -> updateUi.progress = p }
                            updateUi.downloading = false
                            if (f == null) toast.show(ctx.getString(R.string.toast_update_download_failed))
                            else updateUi.apk = f
                        }
                    }
                },
                onInstall = {
                    val a = updateUi.apk
                    if (a != null && !Update.install(ctx, a)) toast.show(ctx.getString(R.string.toast_update_open_failed))
                },
                onClose = { updateUi.open = false },
            )

            restoreUri?.let { u ->
                RestoreSheet(DocumentFile.fromSingleUri(ctx, u)?.name ?: "backup.zip", { restoreUri = null }) { replace ->
                    restoreUri = null
                    scope.launch {
                        app.backup.restore(u, replace).fold(
                            { toast.show(ctx.getString(if (replace) R.string.toast_restored else R.string.toast_merged)) },
                            // The exception message matters: "nothing was changed" and "restored part-way" are
                            // different outcomes that need different messages.
                            { e -> toast.show(e.message ?: ctx.getString(R.string.toast_restore_failed)) },
                        )
                    }
                }
            }
        }
    }
}
