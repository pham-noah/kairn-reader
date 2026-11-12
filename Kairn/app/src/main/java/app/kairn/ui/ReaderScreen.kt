@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kairn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.ui.kit.pressFeedback
import app.kairn.R
import app.kairn.data.BarItem
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
internal enum class ReaderSheet { NONE, DISPLAY, CONTENTS, SEARCH, NOTES, TTS }

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
    val search = ReaderSearchState()
    // Snapshot state on purpose: positions() finishes AFTER the first locator and the bottom bar reads
    // these, so as plain fields the seek scale and "min left" would stay blank until an unrelated repaint.
    var positions by mutableStateOf<List<Locator>>(emptyList())
    var chapterTitles by mutableStateOf<Map<String, String>>(emptyMap())
    var chapterHref by mutableStateOf<String?>(null)
    var chapterProgress by mutableFloatStateOf(0f)          // how far into THIS chapter
    var chapterWords by mutableIntStateOf(0)                // this chapter's word count, once measured
    val speech = ReaderSpeechState()
    var autoScrolling by mutableStateOf(false)              // the page is scrolling by itself
    var bookProgress by mutableFloatStateOf(0f)             // WHOLE-BOOK progression
    /** The book's own word count, counted once after open. 0 until it lands. */
    var bookWords by mutableIntStateOf(0)
    var night by mutableStateOf(false)                      // follow-system, held as state so it can change live
    // Top edge of the reader's bottom bar in window coordinates. A scroll-mode swipe may only start above
    // it: the bar's slider is Compose content in this same window, and intercepting its drag paged a chapter.
    var chromeTopPx by mutableFloatStateOf(Float.MAX_VALUE)
}

/**
 * S-01: the in-book search - the query, its hits, and whether the last page came back full. Its own object
 * because it changes for a different reason than the page does, and because a sheet test can drive it alone.
 */
internal class ReaderSearchState {
    var results by mutableStateOf<List<Locator>>(emptyList())
    var busy by mutableStateOf(false)
    var moreAvailable by mutableStateOf(false)
    var query by mutableStateOf("")
    var chapterOnly by mutableStateOf(false)     // search only the chapter on screen
}

/**
 * R-15: reading aloud as the chrome needs it, and the sleep timer that belongs to the voice.
 */
internal class ReaderSpeechState {
    var on by mutableStateOf(false)              // reading aloud is on (the engine may still be starting)
    var starting by mutableStateOf(false)        // tapped, but the engine has not produced audio yet
    var paused by mutableStateOf(false)          // held at the word it reached
    var sleepChoice by mutableIntStateOf(0)      // 0 = no timer, >0 minutes, -1 = end of chapter
    var cast by mutableStateOf(app.kairn.data.BookCast())   // per book: character voices, and their slots
    var castSpeakers by mutableStateOf<List<String>>(emptyList())  // who speaks in the chapter on screen
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
    fun toggleTts()
    fun stopTts()
    fun setCastEnabled(on: Boolean)
    fun setCastVoice(speaker: String, slot: Int)
    fun setSpeechRate(rate: Float)
    fun setSleepTimer(minutes: Int)
    fun toggleAutoScroll()
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
    colors: ReaderUi,
    isTablet: Boolean,
    toast: ToastState,
    act: ReaderActions,
    wpm: Int = ReaderLogic.WPM,
    /** What the reader switched off in Settings; an empty set is the bar this app has always drawn. */
    hidden: Set<BarItem> = emptySet(),
    /** The order they arranged, modes and tools each drawn in it. */
    order: List<BarItem> = BarItem.DEFAULT_ORDER,
) {
    Box(Modifier.fillMaxSize()) {
        // R-06: warm-light filter is an app-local tint; it never touches system settings.
        if (ui.prefs.warmth > 0f) Box(Modifier.fillMaxSize().background(CColor(0xFFFF9A2E).copy(alpha = ui.prefs.warmth * 0.30f)))
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = K.mint)
        toast.Host(Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp))

        AnimatedVisibility(ui.bars, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Column(Modifier.fillMaxWidth().background(colors.chrome)) {
                Row(Modifier.statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.s_back_to_library), { act.close() }, tint = colors.chromeText)
                    Text(ui.bookTitle, Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, textAlign = ComposeTextAlign.Center, color = colors.chromeText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    // Highlighting lives in the long-press selection menu; the top bar only carries the
                    // bookmark toggle, whose icon and label flip when this chapter has one.
                    val bookmarkHere = remember(ui.marks, ui.chapterHref) {
                        val h = ui.chapterHref
                        h != null && ReaderLogic.chapterHasBookmark(ui.marks, h, ::markHref)
                    }
                    KIconButton(
                        if (bookmarkHere) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                        if (bookmarkHere) stringResource(R.string.s_remove_chapter_bookmark) else stringResource(R.string.s_bookmark_this_chapter),
                        { act.toggleBookmark(bookmarkHere) },
                        tint = colors.chromeText,
                    )
                }
                HorizontalDivider(color = colors.line)
            }
        }

        ui.jumpBack?.let { j ->   // R-08: "Back to where you were"
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

        // While the bars are hidden there is nothing to protect (and the bar is out of composition).
        LaunchedEffect(ui.bars) { if (!ui.bars) ui.chromeTopPx = Float.MAX_VALUE }
        AnimatedVisibility(ui.bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Column(Modifier.fillMaxWidth().background(colors.chrome).onGloballyPositioned { ui.chromeTopPx = it.positionInRoot().y }) {
                HorizontalDivider(color = colors.line)
                Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    SeekBar(ui, colors, act, wpm)
                    // The order is the arrangement, and the first two of it are the bar itself - whatever
                    // they are. A tool placed there becomes a pill like read aloud; a mode pushed past the
                    // second slot waits behind "More" and stays a toggle there.
                    val visible = order.filter { it !in hidden }
                        // Auto scroll exists only in scroll mode, and the speed pill takes its place while
                        // the voice is on: a mode that moves the page must be visible while it moves it.
                        .filter { it != BarItem.AUTO_SCROLL || (ui.prefs.scroll && !ui.speech.on) }
                    val main = visible.take(BarItem.MAIN_SLOTS)
                    val overflow = visible.drop(BarItem.MAIN_SLOTS)
                    // With nothing behind "More" the slots share the row; with an overflow, "More" takes the
                    // slack so the pills never push it off the edge.
                    val pillWidth = if (overflow.isEmpty()) Modifier.weight(1f) else Modifier
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        main.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.width(6.dp))
                            when (item) {
                                BarItem.READ_ALOUD -> {
                                    if (ui.speech.on) {
                                        ModePill(null, stringResource(R.string.s_speech_rate_value, ui.prefs.speechRate), false, colors) { ui.sheet = ReaderSheet.TTS }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    ModePill(
                                        when {
                                            ui.speech.on && ui.speech.paused -> Icons.Default.PlayArrow
                                            ui.speech.on -> Icons.Default.Pause
                                            else -> Icons.Default.VolumeUp
                                        },
                                        stringResource(
                                            when {
                                                ui.speech.starting -> R.string.s_tts_starting
                                                ui.speech.on && ui.speech.paused -> R.string.s_resume
                                                ui.speech.on -> R.string.s_pause
                                                else -> R.string.s_read_aloud
                                            },
                                        ),
                                        ui.speech.on, colors,
                                        modifier = pillWidth,
                                    ) { act.toggleTts() }
                                }

                                BarItem.AUTO_SCROLL -> ModePill(
                                    if (ui.autoScrolling) Icons.Default.Pause else Icons.Default.ArrowDownward,
                                    stringResource(R.string.s_auto_scroll),
                                    ui.autoScrolling, colors,
                                    modifier = pillWidth,
                                    onClick = act::toggleAutoScroll,
                                )

                                // A tool in one of the two slots: same shape as a pill, it just opens its sheet.
                                else -> ModePill(
                                    toolIcon(item), stringResource(toolLabel(item)), false, colors,
                                    modifier = pillWidth,
                                ) { ui.sheet = toolSheet(item) }
                            }
                        }
                        if (overflow.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            val moreInteraction = remember { MutableInteractionSource() }
                            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                                .pressFeedback(moreInteraction, pressedScale = 0.97f)
                                .clickable(interactionSource = moreInteraction, indication = LocalIndication.current) { expanded = !expanded },
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.chromeText, modifier = Modifier.size(20.dp))
                                Text(if (expanded) stringResource(R.string.s_less) else stringResource(R.string.s_more), color = colors.chromeText, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    AnimatedVisibility(expanded) {
                        Row(Modifier.fillMaxWidth()) {
                            // Everything past the second slot, in the arranged order - modes still toggle.
                            overflow.forEach { item ->
                                val live = when (item) {
                                    BarItem.READ_ALOUD -> ui.speech.on
                                    BarItem.AUTO_SCROLL -> ui.autoScrolling
                                    else -> false
                                }
                                ReaderTool(toolIcon(item, live), stringResource(toolLabel(item)), colors, live = live) {
                                    when (item) {
                                        BarItem.READ_ALOUD -> act.toggleTts()
                                        BarItem.AUTO_SCROLL -> act.toggleAutoScroll()
                                        else -> ui.sheet = toolSheet(item)
                                    }
                                }
                            }
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
        LaunchedEffect(ui.sheet) { if (ui.sheet != ReaderSheet.SEARCH) { ui.search.results = emptyList(); ui.search.busy = false; ui.search.moreAvailable = false } }
        if (ui.sheet != ReaderSheet.NONE) KSheet({ ui.sheet = ReaderSheet.NONE }, when (ui.sheet) {
            ReaderSheet.DISPLAY -> stringResource(R.string.s_display)
            ReaderSheet.CONTENTS -> stringResource(R.string.s_contents)
            ReaderSheet.SEARCH -> stringResource(R.string.s_search_in_book)
            ReaderSheet.TTS -> stringResource(R.string.s_reading_aloud)
            else -> stringResource(R.string.s_notes_and_highlights)
        }) {
            when (ui.sheet) {
                ReaderSheet.TTS -> TtsSheet(
                    ui.prefs, ui.speech.sleepChoice, act::setSpeechRate, act::setSleepTimer, act::stopTts,
                    cast = ui.speech.cast, speakers = ui.speech.castSpeakers,
                    onCastEnabled = act::setCastEnabled, onCastVoice = act::setCastVoice,
                )
                ReaderSheet.DISPLAY -> DisplaySheet(ui.prefs, act::setPrefs, showColumns = isTablet, onReset = act::resetPrefs)
                ReaderSheet.CONTENTS -> ContentsSheet(ui.pub, ui.chapterHref, act::goTo)
                ReaderSheet.SEARCH -> {
                    val here = ui.chapterHref
                    val shown = if (ui.search.chapterOnly && here != null) ui.search.results.filter { it.href.toString() == here }
                                else ui.search.results
                    SearchSheet(
                        shown, ui.search.busy, ui.search.moreAvailable && ui.search.query.length >= 3,
                        act::search, act::searchMore, act::goTo, ui.chapterTitles,
                        chapterOnly = ui.search.chapterOnly,
                        onChapterOnly = { ui.search.chapterOnly = it },
                        showChapterFilter = here != null,
                    )
                }
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

/**
 * One reading mode as a toggle: its own state is on the bar, so starting or stopping it never needs a sheet.
 */
@Composable
/** The tool's glyph, label and sheet. One place, so the bar and the setting cannot disagree. */
private fun toolIcon(item: BarItem, live: Boolean = false): ImageVector = when (item) {
    BarItem.READ_ALOUD -> if (live) Icons.Default.Pause else Icons.Default.VolumeUp
    BarItem.AUTO_SCROLL -> if (live) Icons.Default.Pause else Icons.Default.ArrowDownward
    BarItem.CONTENTS -> Icons.AutoMirrored.Filled.List
    BarItem.SEARCH -> Icons.Default.Search
    BarItem.NOTES -> Icons.Default.StickyNote2
    else -> Icons.Default.TextFormat
}

private fun toolLabel(item: BarItem): Int = when (item) {
    BarItem.READ_ALOUD -> R.string.s_read_aloud
    BarItem.AUTO_SCROLL -> R.string.s_auto_scroll
    BarItem.CONTENTS -> R.string.s_tool_contents
    BarItem.SEARCH -> R.string.s_tool_search
    BarItem.NOTES -> R.string.s_tool_notes
    else -> R.string.s_tool_display
}

private fun toolSheet(item: BarItem): ReaderSheet = when (item) {
    BarItem.READ_ALOUD -> ReaderSheet.TTS
    BarItem.AUTO_SCROLL -> ReaderSheet.DISPLAY      // never reached: the bar toggles it instead
    BarItem.CONTENTS -> ReaderSheet.CONTENTS
    BarItem.SEARCH -> ReaderSheet.SEARCH
    BarItem.NOTES -> ReaderSheet.NOTES
    else -> ReaderSheet.DISPLAY
}

@Composable
private fun ModePill(
    icon: ImageVector?,
    label: String,
    live: Boolean,
    colors: ReaderUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (live) K.mint else colors.chromeText
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (live) K.mint.copy(alpha = 0.16f) else CColor.Transparent)
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * The seek bar and the time left, in a leaf of their own: the reader writes bookProgress on every locator, and
 * read in ReaderScreen's own scope that re-executed the whole chrome on every page turn.
 */
@Composable
private fun SeekBar(ui: ReaderUiState, colors: ReaderUi, act: ReaderActions, wpm: Int) {
    val ro = ui.pub?.readingOrder.orEmpty()
    var drag by remember { mutableStateOf<Float?>(null) }
    // Preload the chapter under the slider while dragging, so releasing it feels instant.
    val preloadJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val scope = rememberCoroutineScope()
    fun previewSeek(v: Float) {
        preloadJob[0]?.cancel()
        preloadJob[0] = scope.launch {
            delay(220)
            ui.positions.getOrNull((v * (ui.positions.size - 1)).toInt())?.let(act::preload)
        }
    }
    val preview = drag?.let { v ->
        val idx = (v * (ui.positions.size - 1)).toInt()
        val loc = ui.positions.getOrNull(idx)
        val title = loc?.let { ui.chapterTitles[it.href.toString()] ?: ui.chapterTitles[it.href.toString().substringAfterLast('/')] ?: it.title }
        title?.takeIf { it.isNotBlank() }
            ?: ro.getOrNull((v * (ro.size - 1)).toInt())?.title?.takeIf { it.isNotBlank() }
    }
    // Tap the label to switch between the whole book and the chapter you are in.
    var chapterTime by remember { mutableStateOf(false) }
    val minsLeft = if (chapterTime) ReaderLogic.minutesLeftInChapter(ui.chapterWords, ui.chapterProgress, wpm)
        else ReaderLogic.minutesLeftInBook(ui.bookWords, ui.bookProgress, wpm)
            ?: ReaderLogic.minutesLeft(ui.pace, ui.positions.size, ui.bookProgress, wpm)
    val timeHint = stringResource(if (chapterTime) R.string.s_time_in_chapter else R.string.s_time_in_book)
    val timeLeft = when {
        minsLeft == null -> ""
        minsLeft <= 0 -> stringResource(R.string.time_left_under_a_min)
        minsLeft < 60 -> stringResource(R.string.time_left_mins, minsLeft)
        else -> stringResource(R.string.time_left_hours, minsLeft / 60, minsLeft % 60)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(preview ?: ui.chapterTitle, color = if (preview != null) colors.accent else colors.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        // 48dp of touch, and the value stays the spoken text: the mode is the state, not a replacement for it.
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { chapterTime = !chapterTime }
                .padding(start = 8.dp)
                .semantics { stateDescription = timeHint },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(timeLeft, color = colors.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
    KSlider(drag ?: ui.bookProgress, { v -> drag = v; previewSeek(v) }, accent = colors.accent, trackColor = colors.line,
        description = stringResource(R.string.s_reading_progress), stateText = timeLeft, onFinished = {   // R-07: seek by position
            drag?.let { v ->
                val l = ui.positions.getOrNull((v * (ui.positions.size - 1)).toInt())
                if (l != null) act.goTo(l)
                else ui.pub?.let { p -> ro.getOrNull((v * (ro.size - 1)).toInt())?.let { lk -> p.locatorFromLink(lk)?.let(act::goTo) } }
            }
            drag = null
        })
}
