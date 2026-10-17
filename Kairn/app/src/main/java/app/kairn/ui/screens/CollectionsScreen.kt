package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(store: Store, onOpenShelf: (String) -> Unit) {
    val kinds = remember { listOf("Collection", "Tag") }   // hoisted: inline listOf is a new instance per recomposition
    val books by store.books.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf<Int?>(null) }   // 0 = collection, 1 = tag

    // Group once per change to `books`; the rows below used to re-scan the whole library per collection and per tag.
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
                        Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { inside.take(3).forEach { b -> KCover(b, store.thumbFile(b.id).path, Modifier.width(28.dp), compact = true, fallbackPath = store.coverFile(b.id).path) } }
                        KChevron()
                    }
                }
            }
        }
        KGroup("Tags") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t ->
                    val n = byTag[t]?.size ?: 0
                    Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(999.dp)).background(K.surf).border(1.dp, K.line, RoundedCornerShape(999.dp)).clickable { onOpenShelf(t) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t, fontWeight = FontWeight.SemiBold, color = K.txt); Text("$n", fontSize = 13.sp, color = K.dim)
                    }
                }
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, K.line, RoundedCornerShape(999.dp)).clickable { creating = 1 }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
}

private fun groupBooks(books: List<Book>, keys: (Book) -> List<String>): Map<String, List<Book>> {
    val m = HashMap<String, MutableList<Book>>()
    books.forEach { b -> keys(b).forEach { m.getOrPut(it) { mutableListOf() }.add(b) } }
    return m
}
