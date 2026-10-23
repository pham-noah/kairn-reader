@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kairn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import app.kairn.ui.kit.*
import app.kairn.ui.screens.AnnotationsContent
import app.kairn.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** The reader's sheets. */
internal enum class ReaderSheet { NONE, DISPLAY, CONTENTS, SEARCH, NOTES }

/**
 * Everything the reader screen draws, in one observable holder.
 * Compose observes the `mutableStateOf` properties of any object, so hoisting them off the Activity loses nothing; what it gains is a screen that is a pure function of this state, and an Activity that keeps only what needs Android: the navigator, gestures and the disk.
 */
internal class ReaderUiState {
    /**
     * The open publication, as STATE. As a parameter read in the Activity's setContent lambda it was
     * captured once (that lambda only recomposes when settings change), so Contents stayed empty and the
     * seek fallback had no reading order for the whole session.
     */
    var pub by mutableStateOf<Publication?>(null)
    /** Book-wide words/positions measured so far; the time-left label reads this. */
    var pace by mutableStateOf(ReaderLogic.Pace())
    var bars by mutableStateOf(true)
    var sheet by mutableStateOf(ReaderSheet.NONE)
    var prefs by mutableStateOf(ReaderPrefs())
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var footnote by mutableStateOf<String?>(null)
    var jumpBack by mutableStateOf<Locator?>(null)
    var noteDraft by mutableStateOf<NoteTarget?>(null)     // a selection waiting for a note body
    var noteEdit by mutableStateOf<Mark?>(null)             // an existing note being edited
    var chapterTitle by mutableStateOf("")
    var bookTitle by mutableStateOf("")
    var marks by mutableStateOf<List<Mark>>(emptyList())
    var searchResults by mutableStateOf<List<Locator>>(emptyList())
    var searchBusy by mutableStateOf(false)
    var searchCap by mutableIntStateOf(100)                 // how many hits the current search asked for
    var lastQuery by mutableStateOf("")
    // Snapshot state on purpose: positions() finishes AFTER the first locator and the bottom bar reads
    // these, so as plain fields the seek scale and "min left" would stay blank until an unrelated repaint.
    var positions by mutableStateOf<List<Locator>>(emptyList())
    var chapterTitles by mutableStateOf<Map<String, String>>(emptyMap())
    var chapterHref by mutableStateOf<String?>(null)
    var bookProgress by mutableFloatStateOf(0f)             // WHOLE-BOOK progression
}

/**
 * What the screen can ask the Activity to do: anything needing the navigator, the writer or an Intent.
 * Everything that only changes state is done by the screen itself.
 */
internal interface ReaderActions {
    fun close()
    fun toggleBars()
    fun goTo(locator: Locator)
    fun preload(locator: Locator)
    fun setPrefs(p: ReaderPrefs)
    fun resetPrefs()
    fun toggleBookmark(bookmarked: Boolean)
    fun saveNote(target: NoteTarget, text: String)
    fun updateNote(id: String, text: String)
    fun deleteMark(m: Mark)
    fun deleteBook()
    fun exportNotes()
    fun search(query: String)
    fun searchMore()
}

/** The chapter a mark's locator lives in, for the once-per-chapter bookmark rule. */
internal fun markHref(m: Mark): String? =
    runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull()?.href?.toString()

/**
 * The reader's chrome: top bar, footnote card, "back to where you were", the seek bar with its tools, and
 * the four sheets. Stateless apart from [ui] and the drag in progress.
 */
@Composable
internal fun ReaderScreen(
    ui: ReaderUiState,
    theme: ThemeId,
    isTablet: Boolean,
    toast: ToastState,
    act: ReaderActions,
) {
    val colors = readerUi(theme)
    Box(Modifier.fillMaxSize()) {
        // R-06: warm-light filter is an app-local tint; it never touches system settings.
        if (ui.prefs.warmth > 0f) Box(Modifier.fillMaxSize().background(CColor(0xFFFF9A2E).copy(alpha = ui.prefs.warmth * 0.30f)))
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = K.mint)
        toast.Host(Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp))

        AnimatedVisibility(ui.bars, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Column(Modifier.fillMaxWidth().background(colors.chrome)) {
                Row(Modifier.statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to library", { act.close() }, tint = colors.chromeText)
                    Text(ui.bookTitle, Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, textAlign = ComposeTextAlign.Center, color = colors.chromeText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    // Highlighting lives in the long-press selection menu; the top bar only carries the
                    // bookmark toggle, whose icon and label flip when this chapter has one.
                    val bookmarkHere = remember(ui.marks, ui.chapterHref) {
                        val h = ui.chapterHref
                        h != null && ReaderLogic.chapterHasBookmark(ui.marks, h, ::markHref)
                    }
                    KIconButton(
                        if (bookmarkHere) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                        if (bookmarkHere) "Remove this chapter's bookmark" else "Bookmark this chapter",
                        { act.toggleBookmark(bookmarkHere) },
                        tint = colors.chromeText,
                    )
                }
                HorizontalDivider(color = colors.line)
            }
        }

        ui.jumpBack?.let { j ->   // R-08: "Back to where you were"
            // A short-lived offer: it clears itself after a delay, and any new jump re-arms it.
            LaunchedEffect(j) { delay(6000); ui.jumpBack = null }
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp)).background(K.mint)
                .clickable { act.goTo(j); ui.jumpBack = null }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, tint = K.mintInk, modifier = Modifier.size(20.dp)); Text(stringResource(R.string.s_back_to_where_you_were), color = K.mintInk, fontWeight = FontWeight.ExtraBold)
            }
        }

        ui.footnote?.let { note ->   // R-08: footnote card, so you do not lose your page
            Column(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 150.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.surf2).padding(16.dp)) {
                Text(stringResource(R.string.s_footnote), color = K.mint, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                Text(note, style = KType.small, modifier = Modifier.padding(bottom = 12.dp))
                KButton(stringResource(R.string.s_close), { ui.footnote = null }, style = KButtonStyle.Secondary, small = true)
            }
        }

        AnimatedVisibility(ui.bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Column(Modifier.fillMaxWidth().background(colors.chrome)) {
                HorizontalDivider(color = colors.line)
                Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
                    val ro = ui.pub?.readingOrder.orEmpty()
                    var drag by remember { mutableStateOf<Float?>(null) }
                    var expanded by remember { mutableStateOf(false) }
                    // Preload the chapter under the slider while dragging so releasing feels instant,
                    val preloadJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
                    val scope = rememberCoroutineScope()
                    fun previewSeek(v: Float) {
                        preloadJob[0]?.cancel()
                        preloadJob[0] = scope.launch {
                            delay(220)
                            ui.positions.getOrNull((v * (ui.positions.size - 1)).toInt())?.let(act::preload)
                        }
                    }
                    // While scrubbing, the label names the chapter the slider points at.
                    val preview = drag?.let { v ->
                        val idx = (v * (ui.positions.size - 1)).toInt()
                        val loc = ui.positions.getOrNull(idx)
                        val title = loc?.let { ui.chapterTitles[it.href.toString()] ?: ui.chapterTitles[it.href.toString().substringAfterLast('/')] ?: it.title }
                        title?.takeIf { it.isNotBlank() }
                            ?: ro.getOrNull((v * (ro.size - 1)).toInt())?.title?.takeIf { it.isNotBlank() }
                    }
                    // Formatting is a resource job (i18n); the arithmetic stays in ReaderLogic, where it is tested.
                    val minsLeft = ReaderLogic.minutesLeft(ui.pace, ui.positions.size, ui.bookProgress)
                    val timeLeft = when {
                        minsLeft == null -> ""
                        minsLeft <= 0 -> stringResource(R.string.time_left_under_a_min)
                        minsLeft < 60 -> stringResource(R.string.time_left_mins, minsLeft)
                        else -> stringResource(R.string.time_left_hours, minsLeft / 60, minsLeft % 60)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(preview ?: ui.chapterTitle, color = if (preview != null) colors.accent else colors.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(timeLeft, color = colors.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    KSlider(drag ?: ui.bookProgress, { v -> drag = v; previewSeek(v) }, accent = colors.accent, trackColor = colors.line, onFinished = {   // R-07: seek by position
                        drag?.let { v ->
                            val l = ui.positions.getOrNull((v * (ui.positions.size - 1)).toInt())
                            if (l != null) act.goTo(l)
                            else ui.pub?.let { p -> ro.getOrNull((v * (ro.size - 1)).toInt())?.let { lk -> p.locatorFromLink(lk)?.let(act::goTo) } }
                        }
                        drag = null
                    })
                    // The bar stays minimal; the tools live behind "Options".
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.chromeText, modifier = Modifier.size(20.dp))
                        Text(if (expanded) stringResource(R.string.s_less) else stringResource(R.string.s_options), color = colors.chromeText, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                    AnimatedVisibility(expanded) {
                        Row(Modifier.fillMaxWidth()) {
                            ReaderTool(Icons.AutoMirrored.Filled.List, "Contents", colors) { ui.sheet = ReaderSheet.CONTENTS }
                            ReaderTool(Icons.Default.Search, "Search", colors) { ui.sheet = ReaderSheet.SEARCH }
                            ReaderTool(Icons.Default.StickyNote2, "Notes", colors) { ui.sheet = ReaderSheet.NOTES }
                            ReaderTool(Icons.Default.TextFormat, "Display", colors) { ui.sheet = ReaderSheet.DISPLAY }
                        }
                    }
                }
            }
        }

        if (ui.error != null) KSheet({ act.close() }, stringResource(R.string.bad_file_title)) {
            Text(stringResource(R.string.bad_file_body), style = KType.body.copy(color = K.mut))
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KButton(stringResource(R.string.s_keep_it), { act.close() })
                KButton(stringResource(R.string.s_remove_from_library), { act.deleteBook(); act.close() }, style = KButtonStyle.Danger)
            }
        }
        ui.noteDraft?.let { t -> KSheet({ ui.noteDraft = null }, stringResource(R.string.s_add_a_note)) { NoteEditor(t) { text -> act.saveNote(t, text) } } }
        ui.noteEdit?.let { m ->
            KSheet({ ui.noteEdit = null }, stringResource(R.string.s_edit_note)) {
                // Hoisted: stringResource cannot be called from the save callback below.
                val noteUpdated = stringResource(R.string.toast_note_updated)
                NoteEditor(NoteTarget(m.quote, m.locator), initial = m.note) { text ->
                    act.updateNote(m.id, text); ui.noteEdit = null; toast.show(noteUpdated)
                }
            }
        }
        // Leaving the search sheet drops its hits: reopening it with an empty field must not show
        // the previous query's results.
        LaunchedEffect(ui.sheet) { if (ui.sheet != ReaderSheet.SEARCH) { ui.searchResults = emptyList(); ui.searchBusy = false } }
        if (ui.sheet != ReaderSheet.NONE) KSheet({ ui.sheet = ReaderSheet.NONE }, when (ui.sheet) { ReaderSheet.DISPLAY -> stringResource(R.string.s_display); ReaderSheet.CONTENTS -> stringResource(R.string.s_contents); ReaderSheet.SEARCH -> stringResource(R.string.s_search_in_book); else -> stringResource(R.string.s_notes_and_highlights) }) {
            when (ui.sheet) {
                ReaderSheet.DISPLAY -> DisplaySheet(ui.prefs, act::setPrefs, showColumns = isTablet, onReset = act::resetPrefs)
                ReaderSheet.CONTENTS -> ContentsSheet(ui.pub, act::goTo)
                ReaderSheet.SEARCH -> SearchSheet(ui.searchResults, ui.searchBusy, ui.searchResults.size >= ui.searchCap && ui.lastQuery.length >= 3, act::search, act::searchMore, act::goTo)
                ReaderSheet.NOTES -> AnnotationsContent(ui.bookTitle, ui.marks,
                    onGo = { m -> runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull()?.let(act::goTo) },
                    onDelete = act::deleteMark,
                    onEditNote = { m -> ui.noteEdit = m },
                    onExport = act::exportNotes)
                ReaderSheet.NONE -> {}
            }
        }
        BackHandler(ui.sheet != ReaderSheet.NONE) { ui.sheet = ReaderSheet.NONE }
    }
}
