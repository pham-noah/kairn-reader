package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.data.Mark
import app.kairn.ui.kit.*
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.text.DateFormat
import java.util.Date

private val markKinds = listOf(null to "All", "highlight" to "Highlights", "note" to "Notes", "bookmark" to "Bookmarks")   // constant, not rebuilt per recomposition

/** A-02 / A-03: round 4's Notes and highlights list. Used inside the reader's Notes sheet. */
@Composable
fun AnnotationsContent(bookTitle: String, marks: List<Mark>, onGo: (Mark) -> Unit, onDelete: (Mark) -> Unit, onExport: () -> Unit) {
    var filter by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Mark?>(null) }
    val kind = markKinds[filter].first
    // Filtered/sorted list and chip counts only change with the marks or the filter, not on every recomposition.
    val shown = remember(marks, kind) { marks.filter { kind == null || it.kind == kind }.sortedBy { it.created } }
    val counts = remember(marks) { markKinds.map { (k, _) -> marks.count { k == null || it.kind == k } } }

    Column(Modifier.heightIn(max = 560.dp)) {
        Text(bookTitle, style = KType.small, maxLines = 1)
        LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(markKinds.size) { i -> KChip(markKinds[i].second, filter == i, { filter = i }, count = counts[i]) }
        }
        if (shown.isEmpty()) Text("Select text and choose a highlight colour, or tap the bookmark icon.", style = KType.body.copy(color = K.mut), modifier = Modifier.padding(vertical = 12.dp))
        else KCard(Modifier.weight(1f, fill = false)) {
            LazyColumn {
                items(shown.size, key = { shown[it].id }) { i ->
                    val m = shown[i]
                    if (i > 0) HorizontalDivider(color = K.line)
                    // IntrinsicSize.Min: without it the colour bar's fillMaxHeight has no height to fill and stays at its 36dp minimum.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onGo(m) }.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 36.dp).clip(RoundedCornerShape(4.dp)).background(if (m.kind == "bookmark") K.mint else Color(m.color)))
                        Column(Modifier.weight(1f)) {
                            Text(if (m.kind == "bookmark") "Bookmark" + if (m.quote.isNotBlank()) ", ${m.quote}" else "" else m.quote, style = KType.quote, maxLines = 5)
                            if (m.note.isNotBlank()) Text(m.note, style = KType.small, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.bg).padding(horizontal = 12.dp, vertical = 9.dp))
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.created)), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 8.dp))
                        }
                        KIconButton(Icons.Default.Delete, "Delete", { if (m.note.isNotBlank()) confirm = m else onDelete(m) }, tint = K.dim)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        KButton("Export notes", onExport, style = KButtonStyle.Secondary)
    }
    // A note the reader typed is the one thing here that cannot be recreated with a tap: confirm first.
    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this note?") },
            text = { Text("The note you wrote will be removed. This can't be undone.") },
            confirmButton = { TextButton({ onDelete(m); confirm = null }) { Text("Delete", color = K.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
            containerColor = K.surf2,
        )
    }
}

