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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kairn.R
import app.kairn.data.Mark
import app.kairn.ui.kit.*
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.text.DateFormat
import java.util.Date

// kind (null = every kind) to its filter label resource
/** Where a mark sits in the book, for reading order. A locator without locations sorts first. */
private fun positionOf(m: Mark): Double = runCatching {
    org.json.JSONObject(m.locator).optJSONObject("locations")?.optDouble("totalProgression", 0.0) ?: 0.0
}.getOrDefault(0.0)

private val markKinds = listOf(
    null to R.string.s_all, "highlight" to R.string.s_filter_highlights,
    "note" to R.string.s_filter_notes, "bookmark" to R.string.s_filter_bookmarks,
)

/** A-02 / A-03: the Notes and highlights list, used inside the reader's Notes sheet. */
@Composable
fun AnnotationsContent(bookTitle: String, marks: List<Mark>, onGo: (Mark) -> Unit, onDelete: (Mark) -> Unit, onEditNote: (Mark) -> Unit, onExport: () -> Unit) {
    var filter by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Mark?>(null) }
    var query by remember { mutableStateOf("") }
    var byPosition by remember { mutableStateOf(false) }
    val kind = markKinds[filter].first
    // Filtered list and chip counts change only with the marks or the filter, not per recomposition.
    val shown = remember(marks, kind, query, byPosition) {
        marks.filter { kind == null || it.kind == kind }
            .filter { query.isBlank() || it.quote.contains(query, true) || it.note.contains(query, true) }
            .let { list -> if (byPosition) list.sortedBy { positionOf(it) } else list.sortedBy { it.created } }
    }
    val counts = remember(marks) { markKinds.map { (k, _) -> marks.count { k == null || it.kind == k } } }

    Column(Modifier.heightIn(max = 560.dp)) {
        Text(bookTitle, style = KType.small, maxLines = 1)
        LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(markKinds.size) { i -> KChip(stringResource(markKinds[i].second), filter == i, { filter = i }, count = counts[i]) }
        }
        KField(query, { query = it }, placeholder = stringResource(R.string.s_filter_marks), leading = Icons.Default.Search)
        Row(Modifier.padding(top = 8.dp)) {
            KChip(
                stringResource(if (byPosition) R.string.s_sort_by_position else R.string.s_sort_by_date),
                byPosition, { byPosition = !byPosition },
            )
        }
        if (shown.isEmpty()) Text(stringResource(R.string.s_select_text_and_pick_a_colour_to_highlig), style = KType.body.copy(color = K.mut), modifier = Modifier.padding(vertical = 12.dp))
        else KCard(Modifier.weight(1f, fill = false)) {
            LazyColumn {
                items(shown.size, key = { shown[it].id }) { i ->
                    val m = shown[i]
                    if (i > 0) HorizontalDivider(color = K.line)
                    // IntrinsicSize.Min: without it the colour bar's fillMaxHeight has no height to fill and stays at its 36dp minimum.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onGo(m) }.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 36.dp).clip(RoundedCornerShape(4.dp)).background(if (m.kind == "bookmark") K.mint else Color(m.color)))
                        Column(Modifier.weight(1f)) {
                            Text(if (m.kind == "bookmark") stringResource(R.string.s_bookmark_label) + if (m.quote.isNotBlank()) ", ${m.quote}" else "" else m.quote, style = KType.quote, maxLines = 5)
                            if (m.note.isNotBlank()) Text(m.note, style = KType.small, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.bg).clickable { onEditNote(m) }.padding(horizontal = 12.dp, vertical = 9.dp))
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.created)), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 8.dp))
                        }
                        KIconButton(Icons.Default.Delete, stringResource(R.string.s_delete_desc), { if (m.note.isNotBlank()) confirm = m else onDelete(m) }, tint = K.dim)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        KButton(stringResource(R.string.s_export_notes), onExport, style = KButtonStyle.Secondary)
    }
    // A note the reader typed is the one thing here that cannot be recreated with a tap: confirm first.
    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.s_delete_this_note)) },
            text = { Text(stringResource(R.string.s_the_note_you_wrote_will_be_removed_this)) },
            confirmButton = { TextButton({ onDelete(m); confirm = null }) { Text(stringResource(R.string.s_delete), color = K.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.s_cancel)) } },
            containerColor = K.surf2,
        )
    }
}

