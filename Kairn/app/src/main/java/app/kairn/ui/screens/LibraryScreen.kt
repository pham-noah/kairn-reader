package app.kairn.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.data.Book
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType

private enum class SortBy(val label: String) { RECENT("Recently read"), TITLE("Title"), AUTHOR("Author"), ADDED("Date added") }

/** Row shapes for the grid, so Lazy can reuse slots when the library list changes. */
private const val CT_GRID = 0
private const val CT_LIST = 1

/**
 * Holder for the callbacks so the item lambdas are stable across recompositions - otherwise every
 * item's lambda is considered changed each frame and nothing in the grid can be skipped.
 */
@androidx.compose.runtime.Stable
private class LibActions(
    val open: (Book) -> Unit,
    val longPress: (Book) -> Unit,
)

/**
 * Grid cell. A SEPARATE composable on purpose: an inlined cell body inside `items {}` is recomposed
 * with the grid and cannot be skipped. Extracted with only stable parameters (Book, the now-@Stable
 * Store, and the stable LibActions holder), it shows as `restartable skippable` in the Compose
 * report - matching how Episteme calls SharedMobileBookCard instead of inlining the card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBookCell(b: Book, store: Store, actions: LibActions) {
    Column(Modifier.combinedClickable(onClick = { actions.open(b) }, onLongClick = { actions.longPress(b) })) {
        KCover(b, store.thumbFile(b.id).path, Modifier.fillMaxWidth(), fallbackPath = store.coverFile(b.id).path)
        Text(b.title, style = KType.body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(b.author, fontSize = 13.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** List row. Same extraction rationale as [GridBookCell]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBookRow(b: Book, store: Store, actions: LibActions) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { actions.open(b) }, onLongClick = { actions.longPress(b) }).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        KCover(b, store.thumbFile(b.id).path, Modifier.width(46.dp), compact = true, fallbackPath = store.coverFile(b.id).path)
        Column(Modifier.weight(1f)) {
            Text(b.title, style = KType.title.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.author, style = KType.small, maxLines = 1)
            val (t, warn) = when {
                b.missing -> "File missing. Your place and notes are kept." to true
                b.broken -> "Can't be opened" to true
                b.status == Status.FINISHED -> "Finished" to false
                b.progress > 0f -> "${(b.progress * 100).toInt()}% read" to false
                else -> "Not started" to false
            }
            Text(t, fontSize = 13.5.sp, color = if (warn) K.amber else K.dim, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    store: Store, shelf: String?, onClearShelf: () -> Unit,
    onOpen: (Book) -> Unit, onAdd: () -> Unit, onPickFolder: () -> Unit, onPickBook: () -> Unit,
) {
    val toast = LocalToast.current
    // Lifecycle-aware: while the reader is on top this screen is stopped, and the reader saves the position
    // every few seconds (which emits `books`). collectAsState() would re-filter and re-sort the whole
    // library behind the reader each time; this stops collecting until the screen is visible again.
    val books by store.books.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }
    var grid by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf(SortBy.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Book?>(null) }
    var actions by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<Book?>(null) }

    // `shelf` is a parameter: a bare read inside remember{ derivedStateOf } would capture the FIRST value
    // forever. rememberUpdatedState keeps the lambda reading the current one.
    val currentShelf by rememberUpdatedState(shelf)
    val filtering by remember { derivedStateOf { query.isNotBlank() || status != null || currentShelf != null } }
    // Mirrors Episteme's LibraryProjector: derive the visible list ONCE per data change, outside the
    // recomposition path. derivedStateOf reads the states it depends on (books, filters) and only
    // recomputes when one of them actually changes - not on every frame of a scroll. (A remember()
    // keyed on `books` would have re-compared the whole list each recomposition - O(n) per frame.)
    val continueBook by remember {
        derivedStateOf {
            books.filter { it.lastRead > 0 && it.status != Status.FINISHED && !it.missing && !it.broken }.maxByOrNull { it.lastRead }
        }
    }
    val cb = continueBook
    val shown by remember {
        derivedStateOf {
            // Read continueBook / filtering INSIDE the lambda: the old `cb`/`filtering` locals were plain
            // vals captured at first composition, so these filters used stale values.
            val continueId = continueBook?.id
            val shelf = currentShelf
            books.filter { b ->
                (shelf == null || shelf in b.collections || shelf in b.tags) && (status == null || b.status == status) &&
                    (query.isBlank() || b.title.contains(query, true) || b.author.contains(query, true)) &&
                    (filtering || b.id != continueId)   // the Continue book is not repeated in the grid
            }.sortedWith(when (sort) {
                SortBy.RECENT -> compareByDescending<Book> { it.lastRead }.thenByDescending { it.added }
                SortBy.TITLE -> compareBy { it.title.lowercase() }
                SortBy.AUTHOR -> compareBy { it.author.lowercase() }
                SortBy.ADDED -> compareByDescending { it.added }
            })
        }
    }
    val statusCounts by remember { derivedStateOf { books.groupingBy { it.status }.eachCount() } }
    val actions_ = remember { LibActions(open = { b -> if (b.missing || b.broken) problem = b else onOpen(b) }, longPress = { b -> actions = b }) }

    @Composable fun Header() = Column {
        KScreenHeader("Library", Modifier.padding(top = 16.dp), leading = { CairnMark(30) }) {
            KIconButton(Icons.Default.Add, "Add books", onAdd, bordered = true)
            KIconButton(Icons.Default.Search, "Search library", { searching = !searching; if (!searching) query = "" }, bordered = true)
            KIconButton(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, if (grid) "Switch to list view" else "Switch to grid view", { grid = !grid }, bordered = true)
        }
        if (searching) KField(query, { query = it }, Modifier.padding(top = 12.dp), placeholder = "Search title or author", leading = Icons.Default.Search)
        LazyRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shelf != null) item { KChip(shelf, true, onClearShelf) }
            item { KChip("All", status == null, { status = null }, count = books.size) }
            // Every status the user can SET has a chip. WANT was settable in Edit details and impossible to
            // filter for, and the `else -> "Finished"` arm labelled any new value as Finished.
            items(Status.entries.filter { it != Status.NONE }) { s ->
                KChip(when (s) { Status.NONE -> "Not yet"; Status.WANT -> "Want"; Status.READING -> "Reading"; Status.FINISHED -> "Finished" }, status == s, { status = if (status == s) null else s }, count = statusCounts[s] ?: 0)
            }
        }
        if (cb != null && !filtering) ContinueCard(cb, store, onOpen)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp, start = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} ${if (shown.size == 1) "book" else "books"}", style = KType.small)
            Box {
                Row(Modifier.heightIn(min = 48.dp).clickable { sortMenu = true }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sort.label, color = K.mut, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Icon(Icons.Default.ArrowDropDown, null, tint = K.mut)
                }
                val sortItems = remember(sort) { SortBy.entries.map { it.label to (it == sort) } }   // hoisted: a fresh list per recomposition cannot skip
                KMenu(sortMenu, { sortMenu = false }, sortItems) { sort = SortBy.entries[it] }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (books.isEmpty() && !store.isLoaded) {
            // The first read of the library is still running. Show the header and nothing else: the "No books
            // yet" empty state used to flash on every cold start before the grid appeared.
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dim.screenPad.dp)) {
                KScreenHeader("Library", Modifier.padding(top = 16.dp), leading = { CairnMark(30) })
            }
        } else if (books.isEmpty()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dim.screenPad.dp)) {
                KScreenHeader("Library", Modifier.padding(top = 16.dp), leading = { CairnMark(30) })
                Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CairnMark(64)
                    Text("No books yet", style = KType.h2.copy(fontSize = 24.sp))
                    Text("Add a folder to watch, or open an EPUB file. Everything stays on your phone.", style = KType.body.copy(color = K.mut), modifier = Modifier.widthIn(max = 280.dp))
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KButton("Watch a folder", onPickFolder, icon = Icons.Default.Folder)
                        KButton("Open a file", onPickBook, style = KButtonStyle.Secondary, icon = Icons.Default.Description)
                    }
                }
            }
        } else if (grid) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentPadding = PaddingValues(start = Dim.screenPad.dp, end = Dim.screenPad.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header() }
                items(shown, key = { it.id }, contentType = { CT_GRID }) { b -> GridBookCell(b, store, actions_) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentPadding = PaddingValues(start = Dim.screenPad.dp, end = Dim.screenPad.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Header() }
                items(shown, key = { it.id }, contentType = { CT_LIST }) { b -> ListBookRow(b, store, actions_) }
            }
        }
    }

    problem?.let { b ->
        KSheet({ problem = null }, if (b.missing) "File missing" else "This book can't be opened") {
            Text(if (b.missing) "The file was moved or deleted. Your place and notes are kept. Put the file back, or remove this entry."
                 else "The file may be damaged or too large. The rest of your data is safe.", style = KType.body.copy(color = K.mut))
            Column(Modifier.padding(top = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KButton("Keep it", { problem = null })
                KButton("Remove from library", { store.remove(b.id); problem = null; toast.show("Removed. The file itself was not touched.") }, style = KButtonStyle.Danger)
            }
        }
    }
    actions?.let { b ->
        KSheet({ actions = null }, b.title) {
            KAction("Edit details, collections and tags", Icons.Default.Edit, { editing = b; actions = null }, divider = false)
            KAction(if (b.status == Status.FINISHED) "Mark as reading" else "Mark as finished", Icons.Default.CheckCircle,
                { store.update(b.id) { it.copy(status = if (it.status == Status.FINISHED) Status.READING else Status.FINISHED) }; actions = null })
            KAction("Remove from library", Icons.Default.Delete, { store.remove(b.id); actions = null; toast.show("Removed. The file itself was not touched.") }, danger = true)
        }
    }
    editing?.let { EditBookSheet(it, store) { editing = null } }
}

@Composable
private fun ContinueCard(b: Book, store: Store, onOpen: (Book) -> Unit) {
    val shape = RoundedCornerShape(Dim.card.dp)
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(shape).background(K.surf).border(1.dp, K.line, shape).clickable { onOpen(b) }.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KCover(b, store.thumbFile(b.id).path, Modifier.width(64.dp), showBadge = false, fallbackPath = store.coverFile(b.id).path)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Continue reading", color = K.mint, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(b.title, style = KType.h2.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B3238))) { Box(Modifier.fillMaxWidth(b.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.mint)) }
            Text("${(b.progress * 100).toInt()}% read", style = KType.small)
        }
    }
}

@Composable
private fun EditBookSheet(b: Book, store: Store, onDone: () -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var author by remember { mutableStateOf(b.author) }
    var status by remember { mutableStateOf(b.status) }
    var cols by remember { mutableStateOf(b.collections.joinToString(", ")) }
    var tags by remember { mutableStateOf(b.tags.joinToString(", ")) }
    fun split(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val opts = listOf(Status.NONE to "None", Status.WANT to "Want", Status.READING to "Reading", Status.FINISHED to "Finished")
    KSheet(onDone, "Edit details") {
        KField(title, { title = it }, label = "Title"); KField(author, { author = it }, label = "Author")
        Text("Status", style = KType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        KSegmented(opts.map { it.second }, opts.indexOfFirst { it.first == status }, { status = opts[it].first })
        KField(cols, { cols = it }, label = "Collections (comma separated)"); KField(tags, { tags = it }, label = "Tags (comma separated)")
        Spacer(Modifier.height(16.dp))
        KButton("Save", { store.update(b.id) { it.copy(title = title.ifBlank { b.title }, author = author, status = status, collections = split(cols), tags = split(tags)) }; onDone() })
    }
}
