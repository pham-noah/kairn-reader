package app.kairn.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.kairn.R
import app.kairn.data.Book
import app.kairn.data.EpubMeta
import app.kairn.data.Library
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.data.annotationsMarkdown
import app.kairn.ui.kit.KButton
import app.kairn.ui.kit.KButtonStyle
import app.kairn.ui.kit.KChevron
import app.kairn.ui.kit.KCover
import app.kairn.ui.kit.KRow
import app.kairn.ui.kit.KSheet
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Everything the file says about itself, in the one place a library cell could not hold it: the series, the
 * description, the publisher, the size - and the actions a reader expects to find on the book rather than in
 * a long-press menu.
 *
 * The description is collapsed to four lines until it is asked for: a sheet that opens at a thousand words of
 * blurb is a sheet nobody reads the buttons of.
 */
@Composable
fun BookDetailSheet(
    book: Book,
    store: Store,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    /** Null in a JVM test: the OPF is only re-read for a record imported before 1.0. */
    library: Library? = null,
) {
    val ctx = LocalContext.current
    var detail by remember(book.id) { mutableStateOf(book) }
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var size by remember(book.id) { mutableStateOf(0L) }

    // An older record has no description or series yet: one read fills it in while the sheet is open.
    LaunchedEffect(book.id) {
        if (!detail.metaRead && library != null) detail = library.ensureMeta(detail)
        size = withContext(Dispatchers.IO) { fileSize(ctx, book) }
    }
    // Follow the store: marking the book finished here must move the button in place.
    val books by store.books.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val live = books.firstOrNull { it.id == book.id }
    LaunchedEffect(live) { if (live != null && live != detail) detail = live }

    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val b = store.get(book.id) ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b.annotationsMarkdown().toByteArray()) } }
            }
        }
    }

    KSheet(onClose, stringResource(R.string.s_about_this_book)) {
        BookDetailContent(
            book = detail, store = store, size = size, expanded = expanded,
            wpm = settings.readingWpm,
            onExpand = { expanded = !expanded },
            onExport = { export.launch("${detail.title.take(40)}-notes.md") },
            onToggleFinished = {
                store.update(detail.id) {
                    it.copy(status = if (it.status == Status.FINISHED) Status.READING else Status.FINISHED)
                }
            },
            onOpen = onOpen, onEdit = onEdit, onRemove = { confirmRemove = true },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.s_remove_from_library)) },
            text = { Text(stringResource(R.string.s_removed_the_file_itself_was_not_touched)) },
            confirmButton = {
                TextButton({ confirmRemove = false; onRemove() }) { Text(stringResource(R.string.s_remove), color = K.danger) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.s_cancel)) } },
            containerColor = K.surf2,
        )
    }
}

/**
 * The sheet's body, without the sheet: a ModalBottomSheet is a Dialog window, and a JVM composition is the
 * one place this content can be drawn and asserted. Everything it needs is a parameter, so the test drives
 * the same code the app does.
 */
@Composable
internal fun BookDetailContent(
    book: Book,
    store: Store,
    size: Long,
    expanded: Boolean,
    wpm: Int,
    onExpand: () -> Unit,
    onExport: () -> Unit,
    onToggleFinished: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        KCover(book, store.thumbFile(book.id).path, Modifier.width(84.dp), fallbackPath = store.coverFile(book.id).path)
        Column(Modifier.weight(1f)) {
            Text(book.title, style = KType.h2, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                book.author.ifBlank { stringResource(R.string.s_not_listed) },
                style = KType.body.copy(color = K.mut), modifier = Modifier.padding(top = 2.dp),
            )
            EpubMeta.seriesLabel(book.series, book.seriesIndex)?.let {
                Text(it, style = KType.small.copy(color = K.mint, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // What is left: a hairline plus the same numbers the shelf shows, from the book's own word count.
    val progressText = progressLine(book, wpm)
    if (progressText != null) {
        Column(Modifier.padding(top = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(K.line, RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(book.progress.coerceIn(0f, 1f)).height(3.dp).background(K.mint, RoundedCornerShape(2.dp)))
            }
            Text(progressText, style = KType.small, modifier = Modifier.padding(top = 6.dp))
        }
    }

    val marks = marksLine(book)
    if (marks != null) Text(marks, style = KType.small.copy(color = K.mut), modifier = Modifier.padding(top = 8.dp))

    val description = book.desc?.takeIf { it.isNotBlank() }
    Text(
        description ?: stringResource(R.string.s_no_description),
        style = KType.body.copy(color = if (description == null) K.dim else K.txt),
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 14.dp),
    )
    if (description != null && description.length > 220) {
        Text(
            if (expanded) stringResource(R.string.s_less) else stringResource(R.string.s_more),
            style = KType.small.copy(color = K.mint, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 6.dp).clickable(onClick = onExpand),
        )
    }

    Column(Modifier.padding(top = 10.dp)) {
        InfoLine(stringResource(R.string.s_publisher), book.publisher)
        InfoLine(stringResource(R.string.s_published), book.published)
        InfoLine(stringResource(R.string.s_language), book.language)
        InfoLine(stringResource(R.string.s_file_size), if (size > 0) sizeLabel(size) else null)
    }

    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KButton(
            if (book.progress > 0f) stringResource(R.string.continue_reading) else stringResource(R.string.s_start_reading),
            onOpen,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton(
                if (book.status == Status.FINISHED) stringResource(R.string.s_mark_as_reading) else stringResource(R.string.s_mark_as_finished),
                onToggleFinished,
                style = KButtonStyle.Secondary, small = true, icon = Icons.Default.CheckCircle,
            )
            KButton(stringResource(R.string.s_edit_details), onEdit, style = KButtonStyle.Secondary, small = true, icon = Icons.Default.Edit)
        }
        if (book.marks.isNotEmpty()) {
            KButton(stringResource(R.string.s_export_notes), onExport, style = KButtonStyle.Secondary, icon = Icons.Default.IosShare)
        }
        KButton(stringResource(R.string.s_remove_from_library), onRemove, style = KButtonStyle.Danger, icon = Icons.Default.Delete)
    }
    Spacer(Modifier.height(6.dp))
}

/** One label and its value, or a dim dash: a row that vanishes for a missing publisher reads as a bug. */
@Composable
private fun InfoLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KType.small.copy(color = K.dim), modifier = Modifier.width(104.dp))
        Text(
            value ?: stringResource(R.string.s_not_listed),
            style = KType.small.copy(color = if (value == null) K.dim else K.txt),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "42% · 2 h 10 min left", or nothing for a book that has never been opened. */
@Composable
private fun progressLine(b: Book, wpm: Int): String? {
    if (b.progress <= 0f) return null
    val pct = (b.progress.coerceIn(0f, 1f) * 100).toInt()
    if (b.status == Status.FINISHED) return stringResource(R.string.s_finished)
    val minutes = app.kairn.ui.ReaderLogic.minutesLeftInBook(b.words, b.progress, wpm) ?: return "$pct%"
    val left = when {
        minutes <= 0 -> stringResource(R.string.time_left_under_a_min)
        minutes < 60 -> stringResource(R.string.time_left_mins, minutes)
        else -> stringResource(R.string.time_left_hours, minutes / 60, minutes % 60)
    }
    return "$pct% · $left"
}

/** "3 highlights · 1 note" - only the kinds the book actually has. */
@Composable
private fun marksLine(b: Book): String? {
    val h = b.marks.count { it.kind == "highlight" }
    val n = b.marks.count { it.kind == "note" }
    val m = b.marks.count { it.kind == "bookmark" }
    val parts = buildList {
        if (h > 0) add(pluralStringResource(R.plurals.s_highlights_n, h, h))
        if (n > 0) add(pluralStringResource(R.plurals.s_notes_n, n, n))
        if (m > 0) add(pluralStringResource(R.plurals.s_bookmarks_n, m, m))
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun fileSize(ctx: Context, b: Book): Long = runCatching {
    val uri = b.uri.toUri()
    if (uri.scheme == "file") uri.path?.let { File(it).length() } ?: 0L
    else DocumentFile.fromSingleUri(ctx, uri)?.length() ?: 0L
}.getOrDefault(0L)

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%d KB", bytes / 1024)
    else -> "$bytes B"
}
