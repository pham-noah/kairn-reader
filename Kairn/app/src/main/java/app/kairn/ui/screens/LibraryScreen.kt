package app.kairn.ui.screens

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import kotlinx.coroutines.delay

import app.kairn.data.BookSearch

import androidx.compose.runtime.LaunchedEffect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.R
import app.kairn.data.Book
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType

private enum class SortBy(@androidx.annotation.StringRes val label: Int) {
    RECENT(R.string.s_sort_recent), TITLE(R.string.s_sort_title), AUTHOR(R.string.s_sort_author), ADDED(R.string.s_sort_added),
}

/** Row shapes for the grid, so Lazy can reuse slots when the library list changes. */
private const val CT_GRID = 0
private const val CT_LIST = 1

/**
 * The tick on a picked book in selection mode. A rounded square, not the cover's "finished" circle:
 * the two share a corner and would read as the same badge.
 */
@Composable
private fun PickMark(modifier: Modifier = Modifier) =
    Box(modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(K.mint), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, null, tint = K.mintInk, modifier = Modifier.size(16.dp))
    }

/** Every collection a book can join: the shelves that exist plus any a book already carries. */
private fun knownCollections(books: List<Book>, shelves: List<String>): List<String> =
    (shelves + books.flatMap { it.collections }).distinct().sortedBy { it.lowercase() }

/** Chips over existing collections, so books join a collection instead of a near-duplicate being typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionChips(
    known: List<String>,
    cols: Set<String>,
    emptyHint: String? = null,
    onToggle: (String) -> Unit,
) {
    if (known.isEmpty()) {
        Text(emptyHint ?: stringResource(R.string.s_no_collections_hint), style = KType.small, modifier = Modifier.padding(start = 4.dp))
        return
    }
    Box(Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState())) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            known.forEach { name -> KChip(name, name in cols, { onToggle(name) }) }
        }
    }
}

/** Holds item callbacks so lambdas stay stable across recompositions (unstable ones block skipping). */
@androidx.compose.runtime.Stable
private class LibActions(
    val open: (Book) -> Unit,
    val longPress: (Book) -> Unit,
)

/**
 * Grid cell. A separate composable on purpose: an inlined cell body inside `items {}` is recomposed
 * with the grid and cannot be skipped. Parameterised with stable types (Book, Store, LibActions).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBookCell(b: Book, store: Store, actions: LibActions, selected: Boolean) {
    Column(Modifier.combinedClickable(onClick = { actions.open(b) }, onLongClick = { actions.longPress(b) })) {
        Box {
            KCover(b, store.thumbFile(b.id).path, Modifier.fillMaxWidth(), fallbackPath = store.coverFile(b.id).path)
            // Picked: a mint ring around the cover, so it reads as "selected" and not as the finished badge.
            if (selected) {
                val shape = remember { RoundedCornerShape(topStart = 8.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 8.dp) }
                Box(Modifier.matchParentSize().border(2.dp, K.mint, shape))
                PickMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Text(b.title, style = KType.body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(b.author, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** List row. Same extraction rationale as [GridBookCell]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBookRow(b: Book, store: Store, actions: LibActions, selected: Boolean) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { actions.open(b) }, onLongClick = { actions.longPress(b) }).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected) PickMark()
        KCover(b, store.thumbFile(b.id).path, Modifier.width(46.dp), compact = true, fallbackPath = store.coverFile(b.id).path)
        Column(Modifier.weight(1f)) {
            Text(b.title, style = KType.title.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.author, style = KType.small, maxLines = 1)
            val (t, warn) = when {
                b.missing -> stringResource(R.string.s_list_file_missing) to true
                b.broken -> stringResource(R.string.s_list_broken) to true
                b.status == Status.FINISHED -> stringResource(R.string.s_list_finished) to false
                b.progress > 0f -> stringResource(R.string.s_list_percent_read, (b.progress * 100).toInt()) to false
                else -> stringResource(R.string.s_list_not_started) to false
            }
            Text(t, fontSize = 14.sp, color = if (warn) K.amber else K.dim, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    store: Store, shelf: String?, onShelf: (String?) -> Unit,
    onOpen: (Book) -> Unit, onAdd: () -> Unit, onPickFolder: () -> Unit, onPickBook: () -> Unit,
    onBackup: () -> Unit = {},
    onOpenAt: (Book, String) -> Unit = { _, _ -> },
) {
    val toast = LocalToast.current
    val ctx = LocalContext.current
    // Lifecycle-aware: while the reader is on top it saves position every few seconds (emitting `books`).
    // collectAsState() would re-filter and re-sort the library behind it; this stops until the screen shows.
    val books by store.books.collectAsStateWithLifecycle()
    // Observable: an empty library emits nothing on `books`, so the first read finishing must be a state
    // change of its own or the empty state never replaces the placeholder.
    val loaded by store.loaded.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // Full-text hits live beside the title filter: the same field searches the text once it has 3 characters.
    var hits by remember { mutableStateOf<List<BookSearch.Hit>>(emptyList()) }
    var scanned by remember { mutableStateOf(0 to 0) }
    var scanBusy by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        hits = emptyList()
        scanned = 0 to 0
        scanBusy = false
        if (query.trim().length < 3) return@LaunchedEffect
        delay(500)                                  // typing is not a search yet
        scanBusy = true
        withContext(Dispatchers.IO) {
            BookSearch.run(
                ctx, books, query,
                onProgress = { done, total -> scanned = done to total },
                emit = { hit -> hits = hits + hit },
            )
        }
        scanBusy = false
    }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }
    var grid by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf(SortBy.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortFlipped by remember(sort) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Book?>(null) }
    var actions by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }
    var quickAdd by remember { mutableStateOf<Book?>(null) }   // "add this book to a collection" chips
    // Selection mode: tick several books, then put them all into one collection.
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkTo by remember { mutableStateOf(false) }
    var confirmBulkRemove by remember { mutableStateOf(false) }
    val shelves by store.settings.collectAsStateWithLifecycle()
    val knownCols = remember(books, shelves.shelves) { knownCollections(books, shelves.shelves) }

    // `shelf` is a parameter: a bare read inside remember{ derivedStateOf } would capture the first
    // value forever; rememberUpdatedState keeps reading the current one.
    val currentShelf by rememberUpdatedState(shelf)
    val filtering by remember { derivedStateOf { query.isNotBlank() || status != null || currentShelf != null } }
    // Derive the visible list once per data change, outside the recomposition path: derivedStateOf reads
    // its dependencies (books, filters) and recomputes only when one changes, not on every scroll frame.
    // "Continue reading" as a shelf instead of one card: the last few books, newest first, so the one you
    // were in is not the only one you can get back to without searching.
    val recent by remember {
        derivedStateOf {
            // A finished book is done: it stays in the grid, but it is not something to continue.
            books.filter { it.lastRead > 0 && !it.missing && !it.broken && it.status != Status.FINISHED }
                .sortedByDescending { it.lastRead }
                .take(8)
        }
    }
    val shown by remember {
        derivedStateOf {
            // Read currentShelf inside the lambda: plain vals captured at first composition would be stale.
            val shelf = currentShelf
            books.filter { b ->
                (shelf == null || shelf in b.collections || shelf in b.tags) && (status == null || b.status == status) &&
                    (query.isBlank() || b.title.contains(query, true) || b.author.contains(query, true))
            }.sortedWith(run {
                val natural: Comparator<Book> = when (sort) {
                    SortBy.RECENT -> compareByDescending<Book> { it.lastRead }.thenByDescending { it.added }
                    SortBy.TITLE -> compareBy { it.title.lowercase() }
                    SortBy.AUTHOR -> compareBy { it.author.lowercase() }
                    SortBy.ADDED -> compareByDescending { it.added }
                }
                if (sortFlipped) natural.reversed() else natural
            })
        }
    }
    val statusCounts by remember { derivedStateOf { books.groupingBy { it.status }.eachCount() } }
    // The LibActions holder is created once, so a captured callback would be frozen at its first value.
    // rememberUpdatedState reads the current one while keeping the holder stable.
    val currentOpen by rememberUpdatedState(onOpen)
    val actions_ = remember {
        // One pick-toggle, shared by taps and long presses while selection mode is on.
        fun pick(b: Book) { picked = if (b.id in picked) picked - b.id else picked + b.id }
        LibActions(
            open = { b -> if (selecting) pick(b) else if (b.missing || b.broken) problem = b else currentOpen(b) },
            longPress = { b -> if (selecting) pick(b) else actions = b },
        )
    }

    @Composable fun Header() = Column {
        KScreenHeader(stringResource(R.string.tab_library), Modifier.padding(top = 16.dp), leading = { CairnMark(30) }) {
            KIconButton(Icons.Default.Add, stringResource(R.string.s_add_books), onAdd, bordered = true)
            KIconButton(Icons.Default.Search, stringResource(R.string.s_search_library), { searching = !searching; if (!searching) query = "" }, bordered = true)
            KIconButton(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, stringResource(if (grid) R.string.s_switch_to_list else R.string.s_switch_to_grid), { grid = !grid }, bordered = true)
        }
        if (searching) KField(query, { query = it }, Modifier.padding(top = 12.dp), placeholder = stringResource(R.string.search_hint), leading = Icons.Default.Search)
        if (searching && (hits.isNotEmpty() || scanBusy)) {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    if (scanBusy) stringResource(R.string.s_searching_books, scanned.first, scanned.second)
                    else pluralStringResource(R.plurals.s_hits_in_books, hits.size, hits.size),
                    style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                hits.take(12).forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onOpenAt(h.book, h.locator.toJSON().toString())
                            query = ""                  // cancels the scan; the reader needs the CPU
                        }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(h.book.title, style = KType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.snippet, style = KType.small.copy(color = K.mut), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        LazyRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shelf != null) item { KChip(shelf, true, { onShelf(null) }) }
            item { KChip(stringResource(R.string.s_all), status == null, { status = null }, count = books.size) }
            // Every status that can be set has a chip, so any new value is filterable instead of folding
            // into Finished.
            items(Status.entries.filter { it != Status.NONE }) { s ->
                KChip(when (s) { Status.NONE -> stringResource(R.string.s_not_yet); Status.WANT -> stringResource(R.string.s_want); Status.READING -> stringResource(R.string.s_reading); Status.FINISHED -> stringResource(R.string.s_finished) }, status == s, { status = if (status == s) null else s }, count = statusCounts[s] ?: 0)
            }
        }
        // Collections are one tap away instead of two screens: the same chips the sheets use.
        if (knownCols.isNotEmpty()) LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(knownCols.size) { i ->
                val name = knownCols[i]
                KChip(name, shelf == name, { onShelf(if (shelf == name) null else name) })
            }
        }
        // A backup is the one chore that costs you your notes if you skip it for long enough.
        val quiet = maxOf(shelves.lastBackup, shelves.lastNudge)
        if (books.isNotEmpty() && System.currentTimeMillis() - quiet > 30L * 24 * 60 * 60 * 1000) {
            // padding: a KCard has none by default, so text sits flush against the rounded clip.
            KCard(Modifier.padding(top = 14.dp), padding = 16) {
                Text(stringResource(R.string.s_backup_nudge), style = KType.title)
                Text(stringResource(R.string.s_backup_nudge_body), style = KType.small, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton(stringResource(R.string.s_back_up_now), onBackup, small = true)
                    KButton(stringResource(R.string.s_later), { store.saveSettings { it.copy(lastNudge = System.currentTimeMillis()) } },
                        style = KButtonStyle.Secondary, small = true)
                }
            }
        }
        if (recent.isNotEmpty() && !filtering) ContinueShelf(recent, store, onOpen)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp, start = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.books_count, shown.size, shown.size), style = KType.small)
            // A Box stacks its children, so the reverse arrow must be a SIBLING of the menu anchor: inside
            // the Box it was drawn on top of the sort label ("Recently read").
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(Modifier.heightIn(min = 48.dp).clickable { sortMenu = true }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(sort.label), color = K.mut, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Icon(Icons.Default.ArrowDropDown, null, tint = K.mut)
                    }
                    val sortItems = SortBy.entries.map { stringResource(it.label) to (it == sort) }
                    KMenu(sortMenu, { sortMenu = false }, sortItems) { sort = SortBy.entries[it] }
                }
                KIconButton(
                    if (sortFlipped) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    stringResource(R.string.s_reverse_sort),
                    { sortFlipped = !sortFlipped },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (books.isEmpty() && !loaded) {
            // The first library read is still running: show the header only, not the empty state, which
            // would flash before the grid appears.
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dim.screenPad.dp)) {
                KScreenHeader(stringResource(R.string.tab_library), Modifier.padding(top = 16.dp), leading = { CairnMark(30) })
            }
        } else if (books.isEmpty()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dim.screenPad.dp)) {
                KScreenHeader(stringResource(R.string.tab_library), Modifier.padding(top = 16.dp), leading = { CairnMark(30) })
                Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CairnMark(64)
                    Text(stringResource(R.string.empty_title), style = KType.h2.copy(fontSize = 24.sp))
                    Text(stringResource(R.string.s_add_a_folder_to_watch_or_open_an_epub_fi), style = KType.body.copy(color = K.mut), modifier = Modifier.widthIn(max = 280.dp))
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KButton(stringResource(R.string.add_folder), onPickFolder, icon = Icons.Default.Folder)
                        KButton(stringResource(R.string.s_open_a_file), onPickBook, style = KButtonStyle.Secondary, icon = Icons.Default.Description)
                    }
                }
            }
        } else if (grid) {
            // Adaptive, not fixed: three columns on a phone, more on a tablet, and the cell never gets
            // narrower than a readable width when the font scale grows.
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentPadding = PaddingValues(start = Dim.screenPad.dp, end = Dim.screenPad.dp, top = if (selecting) 56.dp else 0.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header() }
                if (shown.isEmpty() && filtering) item(span = { GridItemSpan(maxLineSpan) }) { NoMatches { query = ""; status = null; onShelf(null) } }
                items(shown, key = { it.id }, contentType = { CT_GRID }) { b -> GridBookCell(b, store, actions_, b.id in picked) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentPadding = PaddingValues(start = Dim.screenPad.dp, end = Dim.screenPad.dp, top = if (selecting) 56.dp else 0.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Header() }
                if (shown.isEmpty() && filtering) item { NoMatches { query = ""; status = null; onShelf(null) } }
                items(shown, key = { it.id }, contentType = { CT_LIST }) { b -> ListBookRow(b, store, actions_, b.id in picked) }
            }
        }

        // Pinned over the books so the action is always reachable while picking.
        if (selecting) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(K.surf).statusBarsPadding().padding(horizontal = Dim.screenPad.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(pluralStringResource(R.plurals.selected_count, picked.size, picked.size), style = KType.title.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                // Picking several books is only worth it if the bar can also finish or remove them.
                KIconButton(Icons.Default.CheckCircle, stringResource(R.string.s_mark_as_finished), {
                    val n = picked.size
                    picked.forEach { id -> store.update(id) { it.copy(status = Status.FINISHED) } }
                    toast.show(ctx.resources.getQuantityString(R.plurals.books_marked_finished, n, n))
                    selecting = false; picked = emptySet()
                }, tint = K.mint)
                KIconButton(Icons.Default.Delete, stringResource(R.string.s_remove_from_library), { confirmBulkRemove = true }, tint = K.danger)
                KButton(stringResource(R.string.s_add_to_collection), { bulkTo = true }, small = true, enabled = picked.isNotEmpty())
                KButton(stringResource(R.string.s_cancel), { selecting = false; picked = emptySet() }, style = KButtonStyle.Secondary, small = true)
            }
        }
    }
    BackHandler(selecting) { selecting = false; picked = emptySet() }

    // Hoisted: stringResource cannot be called from an onClick lambda.
    val removedToast = stringResource(R.string.s_removed_the_file_itself_was_not_touched)

    problem?.let { b ->
        KSheet({ problem = null }, if (b.missing) stringResource(R.string.missing_file) else stringResource(R.string.bad_file_title)) {
            Text(if (b.missing) stringResource(R.string.s_the_file_was_moved_or_deleted_your_place)
                 else stringResource(R.string.s_bad_file_damaged), style = KType.body.copy(color = K.mut))
            Column(Modifier.padding(top = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KButton(stringResource(R.string.s_keep_it), { problem = null })
                KButton(stringResource(R.string.s_remove_from_library), { store.remove(b.id); problem = null; toast.show(removedToast) }, style = KButtonStyle.Danger)
            }
        }
    }
    actions?.let { b ->
        KSheet({ actions = null }, b.title) {
            KAction(stringResource(R.string.s_edit_details_collections_and_tags), Icons.Default.Edit, { editing = b; actions = null }, divider = false)
            // One tap to file a book, instead of entering selection mode for a single book.
            KAction(stringResource(R.string.s_add_to_a_collection), Icons.Default.BookmarkAdd, { quickAdd = b; actions = null })
            KAction(stringResource(R.string.s_select_several_books), Icons.Default.LibraryAdd, { selecting = true; picked = setOf(b.id); actions = null })
            KAction(if (b.status == Status.FINISHED) stringResource(R.string.s_mark_as_reading) else stringResource(R.string.s_mark_as_finished), Icons.Default.CheckCircle,
                { store.update(b.id) { it.copy(status = if (it.status == Status.FINISHED) Status.READING else Status.FINISHED) }; actions = null })
            KAction(stringResource(R.string.s_remove_from_library), Icons.Default.Delete, { store.remove(b.id); actions = null; toast.show(removedToast) }, danger = true)
        }
    }
    if (confirmBulkRemove) {
        val n = picked.size
        AlertDialog(
            onDismissRequest = { confirmBulkRemove = false },
            title = { Text(stringResource(R.string.s_remove_from_library)) },
            text = { Text(pluralStringResource(R.plurals.books_removed, n, n)) },
            confirmButton = {
                TextButton({ picked.forEach { id -> store.remove(id) }; confirmBulkRemove = false; selecting = false; picked = emptySet(); toast.show(removedToast) }) {
                    Text(stringResource(R.string.s_remove), color = K.danger)
                }
            },
            dismissButton = { TextButton({ confirmBulkRemove = false }) { Text(stringResource(R.string.s_cancel)) } },
            containerColor = K.surf2,
        )
    }
    if (bulkTo) KSheet({ bulkTo = false }, stringResource(R.string.s_add_to_collection)) {
        Text(pluralStringResource(R.plurals.books_picked, picked.size, picked.size), style = KType.small, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
        CollectionChips(knownCols, emptySet()) { name ->
            val n = picked.size
            picked.forEach { id -> store.update(id) { b -> b.copy(collections = (b.collections + name).distinct()) } }
            toast.show(ctx.resources.getQuantityString(R.plurals.books_added_to, n, n, name))
            bulkTo = false; selecting = false; picked = emptySet()
        }
        Spacer(Modifier.height(16.dp))
    }
    quickAdd?.let { opened ->
        // Read the live record, so the chips follow the store instead of the copy the sheet opened with.
        val cur = books.firstOrNull { it.id == opened.id } ?: opened
        KSheet({ quickAdd = null }, stringResource(R.string.tab_collections)) {
            Text(stringResource(R.string.s_tap_a_collection_to_put_this_book_in_it), style = KType.small, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
            CollectionChips(knownCols, cur.collections.toSet()) { name ->
                store.update(cur.id) { b -> b.copy(collections = if (name in b.collections) b.collections - name else (b.collections + name).distinct()) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    editing?.let { EditBookSheet(it, store) { editing = null } }
}

/** Shown when a filter or a search leaves nothing: a blank grid with "0 books" above it reads as a bug. */
@Composable
private fun NoMatches(onClear: () -> Unit) =
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.s_no_books_match), style = KType.h2)
        Text(stringResource(R.string.s_try_another_search_or_clear_the_filters), style = KType.body.copy(color = K.mut))
        KButton(stringResource(R.string.s_clear_filters), onClear, style = KButtonStyle.Secondary, small = true)
    }

/** The last few books read, newest first, with the same progress hairline the grid cells carry. */
@Composable
private fun ContinueShelf(list: List<Book>, store: Store, onOpen: (Book) -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            stringResource(R.string.continue_reading),
            color = K.mint, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { b ->
                Column(Modifier.width(94.dp).clickable { onOpen(b) }) {
                    KCover(b, store.thumbFile(b.id).path, Modifier.fillMaxWidth(), showBadge = false, fallbackPath = store.coverFile(b.id).path)
                    // A fixed two-line title box: without it a one-line title pulls its progress bar up and the
                    // shelf's bars sit on four different baselines.
                    Box(Modifier.padding(top = 7.dp).height(36.dp)) {
                        Text(
                            b.title, style = KType.small.copy(fontWeight = FontWeight.SemiBold, lineHeight = 16.sp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2B3238))) {
                        Box(Modifier.fillMaxWidth(b.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.mint))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditBookSheet(b: Book, store: Store, onDone: () -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var author by remember { mutableStateOf(b.author) }
    var status by remember { mutableStateOf(b.status) }
    // Collections are picked from existing ones; a free-text field let a typo mint a near-identical shelf.
    var cols by remember { mutableStateOf(b.collections.toSet()) }
    var newCol by remember { mutableStateOf("") }
    var addingCol by remember { mutableStateOf(false) }
    // Tags stay free text: they are quick throwaway labels and a book can carry many.
    var tags by remember { mutableStateOf(b.tags.joinToString(", ")) }
    fun split(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun addCol() {
        val n = newCol.trim()
        if (n.isEmpty()) return
        store.saveSettings { s -> s.copy(shelves = (s.shelves + n).distinct()) }
        cols = cols + n; newCol = ""; addingCol = false
    }
    val opts = listOf(Status.NONE to R.string.s_status_none, Status.WANT to R.string.s_want, Status.READING to R.string.s_reading, Status.FINISHED to R.string.s_finished)
    val allBooks by store.books.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    // Everything that exists: shelves made on the Collections tab plus any a book arrived carrying.
    val known = remember(allBooks, settings.shelves, b.collections) { knownCollections(allBooks + b, settings.shelves) }
    // Existing tags as chips: typing them out again is how near-duplicates get made.
    val knownTags = remember(allBooks, settings.extraTags) { (settings.extraTags + allBooks.flatMap { it.tags }).distinct().sortedBy { it.lowercase() } }
    KSheet(onDone, stringResource(R.string.s_edit_details)) {
        KField(title, { title = it }, label = stringResource(R.string.s_title)); KField(author, { author = it }, label = stringResource(R.string.s_author))
        Text(stringResource(R.string.s_status), style = KType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        KSegmented(opts.map { stringResource(it.second) }, opts.indexOfFirst { it.first == status }, { status = opts[it].first })
        Text(stringResource(R.string.tab_collections), style = KType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        CollectionChips(known, cols, stringResource(R.string.s_no_collections_here)) { name -> cols = if (name in cols) cols - name else cols + name }
        if (addingCol) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { KField(newCol, { newCol = it }, placeholder = stringResource(R.string.s_new_collection_name)) }
                KButton(stringResource(R.string.s_add), { addCol() }, small = true, enabled = newCol.isNotBlank())
            }
        } else KButton(stringResource(R.string.s_new_collection), { addingCol = true }, Modifier.padding(top = 10.dp), style = KButtonStyle.Secondary, small = true)
        if (knownTags.isNotEmpty()) {
            Text(stringResource(R.string.s_existing_tags), style = KType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
            CollectionChips(knownTags, split(tags).toSet(), stringResource(R.string.s_no_tags_yet)) { t ->
                val cur = split(tags).toSet()
                tags = (if (t in cur) cur - t else cur + t).joinToString(", ")
            }
        }
        KField(tags, { tags = it }, label = stringResource(R.string.s_tags_comma_separated))
        Spacer(Modifier.height(16.dp))
        KButton(stringResource(R.string.s_save), { store.update(b.id) { it.copy(title = title.ifBlank { b.title }, author = author, status = status, collections = cols.sortedBy { c -> c.lowercase() }, tags = split(tags)) }; onDone() })
    }
}
