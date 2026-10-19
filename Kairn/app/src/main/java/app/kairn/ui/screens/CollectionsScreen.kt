package app.kairn.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.data.Book
import app.kairn.data.Store
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType

/** L-08: "Your collections" (shelves you fill) and "Tags" (quick labels) on one tab. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionsScreen(store: Store, onOpenShelf: (String) -> Unit) {
    val kinds = remember { listOf("Collection", "Tag") }   // hoisted: inline listOf is a new instance per recomposition
    val books by store.books.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf<Int?>(null) }   // 0 = collection, 1 = tag
    var manage by remember { mutableStateOf<Pair<String, Int>?>(null) }        // (name, kind) in the rename/delete sheet
    var confirmDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // Group once per change to `books`, not once per collection and per tag row.
    val byCollection = remember(books) { groupBooks(books) { it.collections } }
    val byTag = remember(books) { groupBooks(books) { it.tags } }
    val collections = remember(byCollection, settings.shelves) {
        (settings.shelves + byCollection.keys).distinct().sortedBy { it.lowercase() }
    }
    val tags = remember(byTag, settings.extraTags) {
        (settings.extraTags + byTag.keys).distinct().sortedBy { it.lowercase() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Dim.screenPad.dp).padding(bottom = 24.dp)) {
        KScreenHeader("Collections", Modifier.padding(top = 16.dp)) { KButton("New", { creating = 0 }, style = KButtonStyle.Secondary, small = true) }
        KGroup("Your collections") {
            if (collections.isEmpty()) Text("No collections yet. A collection groups books you choose, like a shelf.", style = KType.small, modifier = Modifier.padding(horizontal = 4.dp))
            else KCard {
                collections.forEachIndexed { i, name ->
                    val inside = byCollection[name].orEmpty()
                    KRow(name, subtitle = "${inside.size} ${if (inside.size == 1) "book" else "books"}", divider = i > 0, onClick = { onOpenShelf(name) }) {
                        Row(Modifier.padding(end = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { inside.take(3).forEach { b -> KCover(b, store.thumbFile(b.id).path, Modifier.width(28.dp), compact = true, fallbackPath = store.coverFile(b.id).path) } }
                        // The row itself opens the collection; the menu holds rename and delete, which used
                        // to be impossible once a name existed.
                        KIconButton(Icons.Default.MoreVert, "Rename or delete $name", { manage = name to 0 }, tint = K.mut)
                    }
                }
            }
        }
        KGroup("Tags") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t ->
                    val n = byTag[t]?.size ?: 0
                    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp)).background(K.surf).border(1.dp, K.line, RoundedCornerShape(999.dp))
                        .combinedClickable(onClick = { onOpenShelf(t) }, onLongClick = { manage = t to 1 })
                        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t, fontWeight = FontWeight.SemiBold, color = K.txt); Text("$n", fontSize = 13.sp, color = K.dim)
                    }
                }
                Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, K.line, RoundedCornerShape(999.dp)).clickable { creating = 1 }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.Add, null, tint = K.mut, modifier = Modifier.size(18.dp)); Text("Tag", fontWeight = FontWeight.SemiBold, color = K.mut)
                }
            }
        }
    }

    creating?.let { first ->
        var kind by remember { mutableIntStateOf(first) }
        var name by remember { mutableStateOf("") }
        KSheet({ creating = null }, "New") {
            KSegmented(kinds, kind, { kind = it })
            Text(if (kind == 0) "A collection groups books you choose, like a shelf." else "A tag is a quick label. A book can have many.", style = KType.small, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            KField(name, { name = it }, placeholder = if (kind == 0) "Collection name" else "Tag name")
            Spacer(Modifier.height(16.dp))
            KButton("Create", {
                val n = name.trim()
                if (n.isNotEmpty()) store.saveSettings { s -> if (kind == 0) s.copy(shelves = (s.shelves + n).distinct()) else s.copy(extraTags = (s.extraTags + n).distinct()) }
                creating = null
            }, enabled = name.isNotBlank())
        }
    }

    // The row itself opens the collection; the menu holds rename and delete.
    manage?.let { (name, kind) ->
        var text by remember(name, kind) { mutableStateOf(name) }
        KSheet({ manage = null }, if (kind == 0) "Collection" else "Tag") {
            KField(text, { text = it }, label = if (kind == 0) "Collection name" else "Tag name")
            Spacer(Modifier.height(14.dp))
            KButton("Save name", {
                val n = text.trim()
                if (n.isNotBlank() && n != name) store.renameLabel(kind, name, n)
                manage = null
            }, enabled = text.isNotBlank() && text.trim() != name)
            Spacer(Modifier.height(10.dp))
            KButton("Delete ${if (kind == 0) "collection" else "tag"}", { confirmDelete = name to kind }, style = KButtonStyle.Danger)
        }
    }

    confirmDelete?.let { (name, kind) ->
        val count = books.count { (if (kind == 0) it.collections else it.tags).contains(name) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("It is removed from $count ${if (count == 1) "book" else "books"}. The books, their notes and their place stay.") },
            confirmButton = {
                TextButton({ store.deleteLabel(kind, name); confirmDelete = null; manage = null }) {
                    Text("Delete", color = K.danger)
                }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
            containerColor = K.surf2,
        )
    }
}

/** kind 0 = collection (settings.shelves + Book.collections), kind 1 = tag (settings.extraTags + Book.tags). */
private fun Store.renameLabel(kind: Int, old: String, new: String) {
    if (kind == 0) saveSettings { s -> s.copy(shelves = s.shelves.map { if (it == old) new else it }.distinct()) }
    else saveSettings { s -> s.copy(extraTags = s.extraTags.map { if (it == old) new else it }.distinct()) }
    books.value.forEach { b ->
        if ((if (kind == 0) b.collections else b.tags).contains(old)) update(b.id) { cur ->
            if (kind == 0) cur.copy(collections = cur.collections.map { if (it == old) new else it }.distinct())
            else cur.copy(tags = cur.tags.map { if (it == old) new else it }.distinct())
        }
    }
}

private fun Store.deleteLabel(kind: Int, name: String) {
    if (kind == 0) saveSettings { s -> s.copy(shelves = s.shelves - name) }
    else saveSettings { s -> s.copy(extraTags = s.extraTags - name) }
    books.value.forEach { b ->
        if ((if (kind == 0) b.collections else b.tags).contains(name)) update(b.id) { cur ->
            if (kind == 0) cur.copy(collections = cur.collections - name) else cur.copy(tags = cur.tags - name)
        }
    }
}
private fun groupBooks(books: List<Book>, keys: (Book) -> List<String>): Map<String, List<Book>> {
    val m = HashMap<String, MutableList<Book>>()
    books.forEach { b -> keys(b).forEach { m.getOrPut(it) { mutableListOf() }.add(b) } }
    return m
}
