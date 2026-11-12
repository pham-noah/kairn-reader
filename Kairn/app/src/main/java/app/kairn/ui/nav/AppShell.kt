package app.kairn.ui.nav

import androidx.core.net.toUri

import app.kairn.ui.ReaderActivity

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import app.kairn.R
import app.kairn.data.Book
import app.kairn.data.ClippingImport
import app.kairn.data.Clippings
import app.kairn.data.OpdsClient
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

/** Full-screen screens that sit above the tabs. */
enum class Overlay { Onboarding, Add, Backup, Catalog }

/**
 * App shell: three tabs, overlays, one toast host, and the system pickers.
 * Screens never talk to Android APIs themselves; they get callbacks from here.
 */
@Composable
internal fun AppShell(deps: ShellDeps, openBook: (Book) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = remember { ToastState() }
    val settings by deps.store.settings.collectAsState()

    var tab by remember { mutableStateOf(Tab.Library) }
    // Decided after the store's first read, never from settings.value here: on a cold start that read has
    // not finished yet, so it still holds the defaults and every returning reader would get onboarding.
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var shelf by remember { mutableStateOf<String?>(null) }
    val cacheStats = rememberCacheStats(deps.store, deps.searchIndex, deps.positionsCache)
    val cacheLabel = remember(cacheStats.value) {
        val (n, bytes) = cacheStats.value
        if (n == 0) "" else ctx.resources.getQuantityString(R.plurals.search_cache_size, n, n, android.text.format.Formatter.formatShortFileSize(ctx, bytes))
    }
    val backupFlow = rememberBackupFlow(deps.store, deps.library, deps.backup, deps.autoBackup, toast, scope)
    val updateUi = remember { UpdateUi() }
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    // DS-04: a store build (F-Droid) cannot update itself, so the whole check disappears there.
    val selfUpdate = remember { ctx.resources.getBoolean(R.bool.self_update) }
    val clipper = remember { ClippingImport(ctx, deps.store, deps.readium) }
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
            deps.store.saveSettings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        }
    }

    LaunchedEffect(Unit) {
        deps.store.awaitLoaded()
        if (!deps.store.settings.value.onboarded) overlay = Overlay.Onboarding
        val s = deps.store.settings.value
        // After the library is up and a moment later still: the check must never delay the first paint.
        if (selfUpdate && s.checkUpdates && System.currentTimeMillis() - s.lastUpdateCheck > 24L * 60 * 60 * 1000) {
            delay(2500)
            checkUpdates(openSheet = false)
        }
    }
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { uris.forEach { deps.library.importPicked(it) }; toast.show(ctx.resources.getQuantityString(R.plurals.books_added, uris.size, uris.size)) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { deps.library.addFolder(u); scope.launch { deps.library.scan(); toast.show(ctx.getString(R.string.toast_watching_folder)) } }
    }
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
    val launchPickFolder = { pickFolder.launch("content://com.android.externalstorage.documents/root/primary".toUri()) }

    BackHandler(overlay != null && overlay != Overlay.Onboarding) {
        // A catalog lives inside Add books: system back steps out one level, not two.
        overlay = if (overlay == Overlay.Catalog) Overlay.Add else null
    }

    CompositionLocalProvider(LocalToast provides toast) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.ambientWash() else Modifier.background(K.bg))) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).then(if (overlay == null) Modifier.tabSwipe(tab) { tab = it } else Modifier)) {
                    when (overlay) {
                        Overlay.Onboarding -> OnboardingScreen(deps.store) { goAdd ->
                            deps.store.saveSettings { it.copy(onboarded = true) }; overlay = if (goAdd) Overlay.Add else null
                        }
                        Overlay.Add -> AddBooksScreen(deps.store, { overlay = null }, launchPickFolder, launchPickBook,
                                onCatalog = { overlay = Overlay.Catalog },
                            onImportClippings = { pickClippings.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                            onImportFile = { file, name ->
                                scope.launch {
                                    val b = deps.library.addBook(Uri.fromFile(file), null, name.removeSuffix(".epub"))
                                    toast.show(if (b.broken) ctx.getString(R.string.toast_could_not_read, name) else ctx.getString(R.string.toast_added, b.title))
                                }
                            })
                        Overlay.Catalog -> CatalogScreen(
                            deps.store, deps.library, OpdsClient(versionName),
                            onBack = { overlay = Overlay.Add },
                            onImported = { n -> toast.show(ctx.resources.getQuantityString(app.kairn.R.plurals.s_books_from_catalog, n, n)) },
                        )
                        Overlay.Backup -> BackupScreen(backupFlow.ui, deps.store, { overlay = null },
                            onBackupNow = { backupFlow.backUpNow() },
                            onRestoreFromFile = { backupFlow.restoreFromFile() },
                            autoFolderName = backupFlow.autoFolderName(),
                            onPickAutoFolder = { backupFlow.pickAutoFolder() },
                            onAutoBackupOff = { deps.store.saveSettings { it.copy(autoBackupFolder = "") } })
                        null -> AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                // A tenth of the width and 85 ms: a change of tab, not a page turn.
                                val shift = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(85)) { w -> shift * w / 10 } + fadeIn(tween(70)))
                                    .togetherWith(slideOutHorizontally(tween(85)) { w -> -shift * w / 10 } + fadeOut(tween(60)))
                            },
                            label = "tab",
                        ) { shown -> when (shown) {
                            Tab.Library -> LibraryScreen(deps.store, shelf, { shelf = it }, openBook, { overlay = Overlay.Add }, launchPickFolder, launchPickBook,
                                onBackup = { overlay = Overlay.Backup },
                                onOpenAt = { b, locator ->
                                    ctx.startActivity(
                                        android.content.Intent(ctx, ReaderActivity::class.java)
                                            .putExtra(ReaderActivity.EXTRA_ID, b.id)
                                            .putExtra(ReaderActivity.EXTRA_LOCATOR, locator)
                                    )
                                }, library = deps.library)
                            Tab.Collections -> CollectionsScreen(deps.store) { shelf = it; tab = Tab.Library }
                            Tab.Settings -> SettingsScreen(deps.store, { overlay = Overlay.Add }, { overlay = Overlay.Backup }, {
                                // Enabling detection is also the moment to ask for the grant it needs.
                                scope.launch { deps.library.scan(); toast.show(ctx.getString(R.string.toast_looking_for_books)) }
                                if (deps.library.needsAllFilesAccess()) deps.library.requestAllFilesAccess()
                            },
                                searchCacheLabel = cacheLabel,
                                onClearSearchCache = {
                                    deps.searchIndex.clear()
                                    deps.positionsCache.clear()
                                    cacheStats.value = 0 to 0L
                                    toast.show(ctx.getString(R.string.s_search_cache_cleared))
                                },
                                onCheckUpdates = { checkUpdates(openSheet = true) },
                                showUpdates = selfUpdate)
                        }
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

            backupFlow.RestoreConfirmation()
        }
    }
}

/**
 * What the shell needs, named one by one rather than handed the Application: the highest-level composable in
 * the app should depend on the seven collaborators it uses, and on nothing else.
 */
internal class ShellDeps(
    val store: app.kairn.data.Store,
    val library: app.kairn.data.Library,
    val backup: app.kairn.data.Backup,
    val autoBackup: app.kairn.data.AutoBackup,
    val searchIndex: app.kairn.data.SearchIndex,
    val positionsCache: app.kairn.data.PositionsCache,
    val readium: app.kairn.data.ReadiumHelper,
)

/**
 * A horizontal flick on the content changes tab, the way the shelf and the reader already respond to a swipe.
 *
 * It gives way to anything that scrolls sideways: a child that consumes the drag cancels this detector before
 * the threshold, so the "Continue reading" shelf keeps its horizontal scroll. 110 dp and a completed drag, so
 * a short nudge does nothing.
 */
private fun Modifier.tabSwipe(tab: Tab, onSwipe: (Tab) -> Unit): Modifier = pointerInput(tab) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            val threshold = 110.dp.toPx()
            val next = when {
                total <= -threshold -> Tab.entries.getOrNull(tab.ordinal + 1)
                total >= threshold -> Tab.entries.getOrNull(tab.ordinal - 1)
                else -> null
            }
            if (next != null) onSwipe(next)
        },
        onHorizontalDrag = { _, dragAmount -> total += dragAmount },
    )
}
