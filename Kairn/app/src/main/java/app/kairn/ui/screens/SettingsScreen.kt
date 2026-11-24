package app.kairn.ui.screens


import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.R
import app.kairn.data.AUTO_SCHEDULE
import app.kairn.data.Accent
import app.kairn.data.Ambient
import app.kairn.data.BarItem
import app.kairn.data.ReaderPrefs
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.data.formatMinutes
import app.kairn.data.minutesOfDay
import app.kairn.data.usesSchedule
import app.kairn.ui.kit.*
import app.kairn.ui.theme.A
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.Glass
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.ReaderUi
import app.kairn.ui.theme.SOLID_WASH
import app.kairn.ui.theme.ambientWash
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface
import app.kairn.ui.theme.readerUi
import app.kairn.ui.theme.solidSurface
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import app.kairn.data.ReadingPreset
import app.kairn.data.BarPreset
import app.kairn.ui.kit.KPresetTile
import app.kairn.ui.kit.PageGlyph
import app.kairn.ui.kit.BarGlyph

// Built once: .copy() at the call sites re-created these on every recomposition (every toggle).
private val SectionTitle = KType.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

/**
 * Three labelled tiles across a phone hold about six characters at 1.5x and four at 2.0x - "Large print" became
 * "Large p..." in the shot. Past 1.5x the preset rows become columns of full-width tiles instead of shrinking type.
 */
private val PresetColumn: Boolean
    @Composable get() = LocalDensity.current.fontScale >= 1.5f

/** The three bar arrangements. */
private val BarPreset.barLabel: Int
    get() = when (this) {
        BarPreset.MINIMAL -> R.string.s_preset_minimal
        BarPreset.STANDARD -> R.string.s_preset_standard
        BarPreset.VOICE -> R.string.s_preset_voice
    }

/** The three reading presets' names, in the six locales the app ships. */
private val ReadingPreset.label: Int
    get() = when (this) {
        ReadingPreset.COMPACT -> R.string.s_preset_compact
        ReadingPreset.COMFORTABLE -> R.string.s_preset_comfortable
        ReadingPreset.LARGE -> R.string.s_preset_large
    }
private val SmallDim = KType.small.copy(color = K.dim)
// A reorder arrow that cannot move this row: visible enough to read as "there is a control here, it is just spent",
// rather than at K.line which vanishes into the card and reads as a missing glyph.

/** null is "follow the system". The names are the languages' own names, deliberately not translated. */
private val languageTags: List<String?> = listOf(null, "en", "es", "fi", "ja", "vi", "zh-CN")
@Composable private fun languageLabel(tag: String?): String = when (tag) {
    null, "" -> stringResource(R.string.s_system)
    "es" -> "Español"
    "fi" -> "Suomi"
    "ja" -> "日本語"
    "vi" -> "Tiếng Việt"
    "zh-CN" -> "简体中文"
    else -> "English"
}

/** N-07: one place for appearance, library, data and about. */
/** The six things the reader's bottom bar can show, in the words the bar itself uses. */
private fun barLabel(item: BarItem): Int = when (item) {
    BarItem.READ_ALOUD -> R.string.s_read_aloud
    BarItem.AUTO_SCROLL -> R.string.s_auto_scroll
    BarItem.CONTENTS -> R.string.s_tool_contents
    BarItem.SEARCH -> R.string.s_tool_search
    BarItem.NOTES -> R.string.s_tool_notes
    BarItem.DISPLAY -> R.string.s_tool_display
}

/** The same glyphs the reader bar draws, so a row and its pill cannot disagree. */
private fun barIcon(item: BarItem): ImageVector = when (item) {
    BarItem.READ_ALOUD -> Icons.AutoMirrored.Filled.VolumeUp
    BarItem.AUTO_SCROLL -> Icons.Default.ArrowDownward
    BarItem.CONTENTS -> Icons.AutoMirrored.Filled.List
    BarItem.SEARCH -> Icons.Default.Search
    BarItem.NOTES -> Icons.AutoMirrored.Filled.StickyNote2
    BarItem.DISPLAY -> Icons.Default.TextFormat
}

/** One row of the bar list. Fixed, because a drag that changes the row's height under the finger is a lie. */
private val BarRowHeight = 56.dp

/**
 * The reader bar's items, in order, moved by long-pressing a row and dragging it.
 *
 * Twelve chevrons went away for this: reordering six rows with two buttons each is eighteen taps to put one
 * item first, and every one of those taps is a place the list can be left half-sorted. A drag is one gesture
 * with no intermediate states to save.
 *
 * Screen readers cannot drag, so each row keeps the two moves as accessibility actions - the gesture is an
 * addition to the old way, not a replacement for anyone who cannot perform it.
 */
@Composable
private fun BarRows(
    order: List<BarItem>, hidden: Set<BarItem>,
    onOrder: (List<BarItem>) -> Unit, onShow: (BarItem, Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    var list by remember(order) { mutableStateOf(order) }
    var dragging by remember { mutableIntStateOf(-1) }
    var lifted by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { BarRowHeight.toPx() }
    val moveUp = stringResource(R.string.s_move_up)
    val moveDown = stringResource(R.string.s_move_down)

    fun move(item: BarItem, delta: Int) {
        val from = list.indexOf(item)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from == to || from < 0) return
        list = list.toMutableList().also { it[from] = list[to]; it[to] = list[from] }
        onOrder(list)
    }

    // The gesture body lives here rather than inside a pointerInput: the grip drags on touch and the rest of the
    // row drags after a long press, and both have to land in exactly the same list.
    fun begin(item: BarItem) {
        dragging = list.indexOf(item)
        lifted = 0f
        haptics.longPress()
    }

    fun dragBy(deltaY: Float) {
        if (dragging < 0) return
        lifted += deltaY
        // Past most of a row, the row trades places and the lift shrinks by the same distance - so the finger
        // keeps hold of the same row instead of it running away.
        val step = if (lifted > rowPx * 0.55f) 1 else if (lifted < -rowPx * 0.55f) -1 else 0
        val to = dragging + step
        if (step != 0 && to in list.indices) {
            val from = dragging
            list = list.toMutableList().also { it[from] = list[to]; it[to] = list[from] }
            lifted -= (to - from) * rowPx
            dragging = to
            haptics.tick()
        }
    }

    fun end() {
        if (dragging >= 0) onOrder(list)
        dragging = -1
        lifted = 0f
    }

    Column {
        list.forEachIndexed { i, item ->
            val held = i == dragging
            Box(
                Modifier.fillMaxWidth().height(BarRowHeight)
                    .zIndex(if (held) 1f else 0f)
                    .graphicsLayer { translationY = if (held) lifted else 0f }
                    // Keyed on the ITEM, never on the list: a swap rewrites the list, and a key that watched it
                    // restarted the gesture on the first swap - the row could be picked up and then went dead
                    // under the finger, which is the "cannot drag it at all" this fixes.
                    .pointerInput(item) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { begin(item) },
                            onDragEnd = { end() },
                            onDragCancel = { end() },
                            onDrag = { change, delta -> change.consume(); dragBy(delta.y) },
                        )
                    },
            ) {
                KRow(
                    title = stringResource(barLabel(item)),
                    leading = barIcon(item),
                    divider = i > 0,
                    modifier = Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(moveUp) { move(item, -1); true },
                            CustomAccessibilityAction(moveDown) { move(item, +1); true },
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KSwitch(item !in hidden, onChange = { on -> onShow(item, on) })
                            // The grip is the thing people reach for, so it drags immediately - no half-second
                            // hold - while the rest of the row stays a long press so a tap still toggles.
                            Box(
                                Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).padding(start = 8.dp)
                                    .pointerInput(item) {
                                        detectDragGestures(
                                            onDragStart = { begin(item) },
                                            onDragEnd = { end() },
                                            onDragCancel = { end() },
                                            onDrag = { change, delta -> change.consume(); dragBy(delta.y) },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.DragHandle, stringResource(R.string.s_drag_to_reorder),
                                    tint = if (held) A.accent else K.dim, modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
/**
 * A swipe as one line: a thumb at the start, a trail, and an arrowhead where the drag has to end. `reach` is where
 * the head lands, so the three tiles differ by the only thing the setting changes.
 */
@Composable
private fun SwipePreview(reach: Float, selected: Boolean) {
    val ink = if (selected) A.accent else K.mut
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(20.dp)) {
        val cy = size.height / 2f
        val r = 3.5.dp.toPx()
        val tip = (size.width - 4.dp.toPx()) * reach
        val head = 4.dp.toPx()
        drawCircle(ink, r, Offset(r, cy))
        drawLine(ink.copy(alpha = 0.5f), Offset(r * 2.4f, cy), Offset(tip - head, cy), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(tip - head, cy - head), Offset(tip, cy), 2.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(tip - head, cy + head), Offset(tip, cy), 2.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * The reader bar, drawn: the two slots in the arranged order, then the More door for the rest. Hiding an item
 * simply removes it from the strip - that is what hiding means - so this row answers "what did I just do" without
 * a sentence explaining the rule.
 */
@Composable
private fun BarPreview(order: List<BarItem>, hidden: Set<BarItem>) {
    val visible = remember(order, hidden) { order.filter { it !in hidden } }
    val main = visible.take(BarItem.MAIN_SLOTS)
    val overflow = visible.drop(BarItem.MAIN_SLOTS)
    Row(
        Modifier.fillMaxWidth().insetWell(RoundedCornerShape(18.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible.isEmpty()) {
            // An empty bar is a real state, and it should look empty rather than look broken.
            Text("—", style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp))
        }
        main.forEach { item ->
            // Equal shares only when there is no More door to leave room for: a 0 weight is a crash, and a fixed
            // width would clip a long label.
            BarPill(
                barIcon(item), stringResource(barLabel(item)),
                if (overflow.isEmpty()) Modifier.weight(1f) else Modifier,
            )
        }
        if (overflow.isNotEmpty()) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.s_more), style = KType.small.copy(color = K.dim), maxLines = 1)
                Icon(Icons.Default.ExpandMore, null, tint = K.dim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** One slot of the previewed bar: the same shape as the reader's pill, in the app's own colours. */
@Composable
private fun BarPill(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
            .then(
                if (glass) Modifier.background(Glass.fill).border(1.dp, Glass.hairline, RoundedCornerShape(12.dp))
                else Modifier.background(K.surf2),
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = K.mut, modifier = Modifier.size(15.dp))
        Text(
            label, style = KType.small.copy(color = K.txt), maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SettingsScreen(
    store: Store, onWatchedFolders: () -> Unit, onBackup: () -> Unit, onScan: () -> Unit,
    onCheckUpdates: () -> Unit = {},
    showUpdates: Boolean = true,
    searchCacheLabel: String = "",
    onClearSearchCache: () -> Unit = {},
) {
    val s by store.settings.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var licences by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var defaultsOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    // Read from AppCompat, not from a file of our own: it is what actually decides the resource locale,
    // and on API 33+ it is backed by the system's per-app language setting.
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { null }
    val stats by store.stats.collectAsStateWithLifecycle()
    val books by store.books.collectAsStateWithLifecycle()
    val finishedCount = remember(books) { books.count { it.status == Status.FINISHED } }
    // One line of proof that the record exists, so the row is worth tapping.
    val statsSub = if (stats.totalMinutes() == 0) stringResource(R.string.s_no_reading_yet)
                   else pluralStringResource(R.plurals.reading_today, stats.minutesToday(), stats.minutesToday(), stats.streak())
    // Hoisted: an inline listOf() is a new instance every recomposition, an unstable argument that blocks
    val themes = remember { listOf(ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.GRAY, ThemeId.DARK, ThemeId.BLACK) }
    val ambients = remember { listOf(Ambient.MINT, Ambient.OCEAN, Ambient.SUNSET, Ambient.PLAIN) }
    // Derived, not a parallel literal list: names and values feed the same swatch row, so they cannot drift
    // out of order.
    val ambientNames = remember(ambients) { ambients.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } }
    val accents = remember { Accent.entries }
    // Six names cannot sit under six dots on a phone, so the row is dots only and the chosen one is named once
    // below it - the same shape at 1.0x and at 2.0x, which is also why there is no large-type branch here.
    val coverName = stringResource(R.string.s_preset_from_cover)
    val accentNames = remember(accents, coverName) {
        accents.associateWith {
            if (it == Accent.MATCH) coverName else it.name.lowercase().replaceFirstChar(Char::uppercase)
        }
    }
    val swipeNames = listOf(R.string.s_swipe_short, R.string.s_swipe_medium, R.string.s_swipe_long)
    val swipeVals = remember { listOf(28, 48, 80) }
    // Where the arrow in each swipe tile ends, as a share of the tile: 28 / 48 / 80 dp of a 100dp track.
    val swipeReach = remember { listOf(0.30f, 0.55f, 0.88f) }
    // The slider's own position while the thumb is down: the stored number only moves when it is let go.
    var speedDraft by remember(s.readingWpm) { mutableFloatStateOf(s.readingWpm.toFloat()) }
    val lastDate = remember(s.lastBackup) { if (s.lastBackup > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.lastBackup)) else null }
    val last = if (lastDate != null) stringResource(R.string.s_last_backup_on, lastDate) else stringResource(R.string.s_no_backup_yet)
    val folders = s.folders.count { it.enabled }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dim.screenPad.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.tab_settings), style = KType.h1, modifier = Modifier.padding(top = 16.dp))
        KGroup(stringResource(R.string.s_group_appearance)) {
            KCard(padding = 16) {
                // Drawn, not named: each tile is the real recipe over the wash you actually chose, so the
                // choice is legible before it is made.
                Text(stringResource(R.string.s_surface), style = SectionTitle, modifier = Modifier.padding(bottom = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KOptionTile(stringResource(R.string.s_surface_glass), s.glass, { store.saveSettings { it.copy(glass = true) } }, Modifier.weight(1f), height = 66) {
                        SurfacePreview(glassStyle = true)
                    }
                    KOptionTile(stringResource(R.string.s_surface_solid), !s.glass, { store.saveSettings { it.copy(glass = false) } }, Modifier.weight(1f), height = 66) {
                        SurfacePreview(glassStyle = false)
                    }
                }
                // The accent is the colour of the things you press; the wash below only tints the background. Two
                // settings, two different jobs, so they sit next to each other and not in one row.
                Text(stringResource(R.string.s_accent), style = SectionTitle, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                // Five names under five dots is fine at 1.0x and collides at 2.0x - "MintOceanSunsetRoseViole/t" in
                // the huge-type shot. Past 1.5x the dots stay and the chosen name is printed once underneath,
                // which cannot collide because it is one word on its own line.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    accents.forEach { a ->
                        KAccentSwatch(
                            a, accentNames[a] ?: a.name, s.accent == a, { store.saveSettings { it.copy(accent = a) } },
                            showLabel = false,
                        )
                    }
                }
                Text(
                    accentNames[s.accent] ?: s.accent.name, style = KType.small, color = K.mut,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
                )
                // Shown for BOTH styles: Solid keeps the wash at a lower strength, so a flat panel still sits on
                // colour instead of on flat black.
                Text(stringResource(R.string.s_ambient_colour), style = SectionTitle, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ambients.forEachIndexed { i, a ->
                        KAmbientSwatch(a, ambientNames[i], s.ambient == a, onClick = { store.saveSettings { it.copy(ambient = a) } })
                    }
                }
                // The same swatches the Display sheet and onboarding use: one control for one choice, app-wide.
                Text(stringResource(R.string.s_reading_theme), style = SectionTitle, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    themes.forEach { t ->
                        KThemeSwatch(t, s.theme == t, { store.saveSettings { it.copy(theme = t, defaults = it.defaults.copy(theme = t)) } })
                    }
                }
                Text(stringResource(R.string.s_switch_automatically), style = SectionTitle, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                // Three answers, not two: off, the phone's own day/night flag, or a window the reader picks. The
                // phone's is a guess about sunset in a city the app has never been to.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KOptionTile(stringResource(R.string.s_auto_off), !s.autoSwitch, { store.saveSettings { it.copy(autoSwitch = false) } }, Modifier.weight(1f), height = 66) {
                        AutoPreview(system = false, theme = s.theme)
                    }
                    KOptionTile(
                        stringResource(R.string.s_auto_system), s.autoSwitch && s.autoMode != AUTO_SCHEDULE,
                        { store.saveSettings { it.copy(autoSwitch = true, autoMode = app.kairn.data.AUTO_SYSTEM) } },
                        Modifier.weight(1f), height = 66,
                    ) { AutoPreview(system = true, theme = s.theme) }
                    KOptionTile(
                        stringResource(R.string.s_auto_schedule), usesSchedule(s),
                        { store.saveSettings { it.copy(autoSwitch = true, autoMode = AUTO_SCHEDULE) } },
                        Modifier.weight(1f), height = 66,
                    ) { AutoPreview(system = true, theme = s.theme) }
                }
                if (usesSchedule(s)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeChip(stringResource(R.string.s_dark_from), s.nightFrom, Modifier.weight(1f)) { m ->
                            store.saveSettings { it.copy(nightFrom = m) }
                        }
                        TimeChip(stringResource(R.string.s_dark_until), s.nightTo, Modifier.weight(1f)) { m ->
                            store.saveSettings { it.copy(nightTo = m) }
                        }
                    }
                }
            }
        }
        KGroup(stringResource(R.string.s_group_reading)) {
            KCard {
                KRow(stringResource(R.string.s_keep_screen_on)) { KSwitch(s.keepScreenOn, { v -> store.saveSettings { it.copy(keepScreenOn = v) } }) }
                KRow(stringResource(R.string.s_full_screen), divider = true) { KSwitch(s.fullscreen, { v -> store.saveSettings { it.copy(fullscreen = v) } }) }
                KRow(stringResource(R.string.s_volume_keys_turn_pages), divider = true) { KSwitch(s.volumeKeys, { v -> store.saveSettings { it.copy(volumeKeys = v) } }) }
                // The tick is the app's own voice: some readers want the phone silent in a quiet room, and a
                // setting is cheaper than an argument about it.
                KRow(stringResource(R.string.s_hide_bars)) { KSwitch(s.hideBars, { v -> store.saveSettings { it.copy(hideBars = v) } }) }
            KRow(stringResource(R.string.s_haptics), divider = true) { KSwitch(s.haptics, { v -> store.saveSettings { it.copy(haptics = v) } }) }
                // The time left is a guess about how fast YOU read; this is that guess. Four words were never a
                // measure of anything - "Normal" is not a number - so the slider states the number and the row
                // says what it is used for.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.s_reading_speed), style = KType.title)
                        Text(
                            stringResource(R.string.s_wpm_value, speedDraft.roundToInt()),
                            style = KType.small.copy(color = A.accent),
                        )
                    }
                    KSlider(
                        speedDraft, { speedDraft = it }, 120f..400f,
                        description = stringResource(R.string.s_reading_speed),
                        stateText = stringResource(R.string.s_wpm_value, speedDraft.roundToInt()),
                        // Written when the thumb is let go: dragging a store write through every frame is work
                        // nobody asked for, and the value on screen comes from the draft anyway.
                        onFinished = { store.saveSettings { it.copy(readingWpm = speedDraft.roundToInt()) } },
                    )
                }
                KRow(stringResource(R.string.s_swipe_to_change_chapter), divider = true) { KSwitch(s.swipeChapters, { v -> store.saveSettings { it.copy(swipeChapters = v) } }) }
                if (s.swipeChapters) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.s_swipe_distance), style = KType.title)
                        // Drawn: the arrow's length IS the swipe distance, so the three tiles are told apart by
                        // the thing they change rather than by three adjectives that mean nothing until tried.
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val picked = when { s.swipeThreshold <= 34 -> 0; s.swipeThreshold <= 62 -> 1; else -> 2 }
                            swipeNames.forEachIndexed { i, name ->
                                KOptionTile(
                                    stringResource(name), picked == i,
                                    { store.saveSettings { it.copy(swipeThreshold = swipeVals[i]) } },
                                    Modifier.weight(1f), height = 44,
                                ) { SwipePreview(swipeReach[i], picked == i) }
                            }
                        }
                    }
                }
                // The selection menu's two optional actions. Off means the item is not built at all - a
                // disabled row in a text-selection toolbar has nowhere to explain itself.
                KRow(stringResource(R.string.s_share_a_passage), divider = true) {
                    KSwitch(s.shareSelection, { v -> store.saveSettings { it.copy(shareSelection = v) } })
                }
                KRow(stringResource(R.string.s_look_a_word_up), divider = true) {
                    KSwitch(s.lookupSelection, { v -> store.saveSettings { it.copy(lookupSelection = v) } })
                }
                // Type size, spacing and margins are per book, so a reader who needs large type otherwise
                // re-sets them on every new book.
                KRow(stringResource(R.string.s_reading_defaults), divider = true, onClick = { defaultsOpen = true }) { KChevron() }
            }
        }
        // What the reader's bar shows, and in what order. Stored as what is HIDDEN plus the arrangement,
        // so a bar item added later still appears for anyone who never touched this.
        KGroup(stringResource(R.string.s_group_reader_bar)) {
            // Three arrangements, then the strip that shows the current one. The list below is for the fourth
            // thing - a bar you arranged yourself - and stops any preset being selected the moment it is touched.
            val barNow = BarPreset.of(s.barOrder)
            if (PresetColumn) {
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarPreset.entries.forEach { preset ->
                        KPresetTile(
                            label = stringResource(preset.barLabel),
                            selected = barNow == preset,
                            onClick = { store.saveSettings { it.copy(barOrder = preset.order) } },
                            modifier = Modifier.fillMaxWidth(), wide = true,
                        ) { BarGlyph(preset.order, s.barHidden) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BarPreset.entries.forEach { preset ->
                        KPresetTile(
                            label = stringResource(preset.barLabel),
                            selected = barNow == preset,
                            onClick = { store.saveSettings { it.copy(barOrder = preset.order) } },
                            modifier = Modifier.weight(1f),
                        ) { BarGlyph(preset.order, s.barHidden) }
                    }
                }
            }
            // Drawn, so the hint paragraph is gone: the strip IS the sentence "the first two sit in the bar, the
            // rest wait behind More", and it moves as the list moves.
            BarPreview(s.barOrder, s.barHidden)
            KCard(modifier = Modifier.padding(top = 10.dp)) {
                BarRows(s.barOrder, s.barHidden, onOrder = { list -> store.saveSettings { it.copy(barOrder = list) } },
                    onShow = { item, on ->
                        store.saveSettings { it.copy(barHidden = if (on) it.barHidden - item else it.barHidden + item) }
                    })
            }
        }

        KGroup(stringResource(R.string.s_group_library)) {
            KCard {
                KRow(stringResource(R.string.s_find_books_on_this_device)) {
                    KSwitch(s.autoDetect, { v ->
                        store.saveSettings { it.copy(autoDetect = v) }
                        if (v) onScan()
                    })
                }
                KRow(stringResource(R.string.s_watched_folders), subtitle = pluralStringResource(R.plurals.folders_count, folders, folders), divider = true, onClick = onWatchedFolders) { KChevron() }
            }
        }
        KGroup(stringResource(R.string.s_group_your_data)) {
            KCard {
                KRow(stringResource(R.string.s_backup_and_restore), subtitle = last, onClick = onBackup, divider = true) { KChevron() }
                // Safe to delete: the dumps rebuild from the books on the next search.
                if (searchCacheLabel.isNotBlank()) KRow(stringResource(R.string.s_search_cache), subtitle = searchCacheLabel, onClick = onClearSearchCache, divider = false) { KChevron() }
                // DS-02: the reading record lives beside the backup, because that is the group about "your stuff".
                KRow(stringResource(R.string.s_your_reading), subtitle = statsSub, divider = true, onClick = { statsOpen = true }) { KChevron() }
            }
        }
        KGroup(stringResource(R.string.s_group_about)) {
            KCard {
                // In the About card rather than a section of its own: it is one row, and a language is
                // something you set once.
                KRow(stringResource(R.string.s_language), subtitle = languageLabel(currentLang), onClick = { langOpen = true }) { KChevron() }
                // DS-04: the one request Kairn makes on its own, with its own switch next to it. A store
                // build (F-Droid) has no updater at all, so the rows are not shown there.
                if (showUpdates) {
                    KRow(stringResource(R.string.s_check_for_updates), divider = true) {
                        KSwitch(s.checkUpdates, { v -> store.saveSettings { it.copy(checkUpdates = v) } })
                    }
                    KRow(stringResource(R.string.s_check_now), divider = true, onClick = onCheckUpdates) { KChevron() }
                }
                // The credit sits above the licences and the issue tracker: it is who made the app, not a
                // utility. The link opens the browser; Kairn has no web code of its own.
                KRow(stringResource(R.string.s_made_by_noah_pham), subtitle = stringResource(R.string.s_github_com_pham_noah), divider = true, onClick = {
                    openRow(ctx, "https://github.com/pham-noah")
                }) { KChevron() }
                // The source, one row under the credit. Same pattern: the browser opens the page, so Kairn
                // keeps no web code and no network permission of its own for links.
                KRow(stringResource(R.string.s_kairn_on_github), subtitle = stringResource(R.string.s_source_code_issues_and_releases), divider = true, onClick = {
                    openRow(ctx, "https://github.com/pham-noah/kairn-reader")
                }) { KChevron() }
                KRow(stringResource(R.string.s_open_source_licences), divider = true, onClick = { licences = true }) { KChevron() }
                // No network code of our own: the browser opens the tracker.
                KRow(stringResource(R.string.s_report_problem), divider = false, onClick = {
                    openRow(ctx, "https://github.com/pham-noah/kairn-reader/issues")
                }) { KChevron() }
            }
        }
        Text(stringResource(R.string.s_footer_tagline), style = KType.small.copy(color = K.dim, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 6.dp, end = 6.dp))
    }

    if (statsOpen) StatsSheet(stats, finishedCount, s.dailyGoal, { g -> store.saveSettings { it.copy(dailyGoal = g) } }) { statsOpen = false }
    if (licences) KSheet({ licences = false }, stringResource(R.string.s_open_source_licences)) {
        KCard {
            KRow(stringResource(R.string.s_readium_kotlin_toolkit), subtitle = stringResource(R.string.s_bsd_3_clause_renders_your_books))
            KRow(stringResource(R.string.s_manrope_and_literata), subtitle = stringResource(R.string.s_sil_open_font_license_bundled_with_the_a), divider = true)
            KRow(stringResource(R.string.s_libron), subtitle = stringResource(R.string.s_libron_licence))
        }
    }
    if (defaultsOpen) KSheet({ defaultsOpen = false }, stringResource(R.string.s_reading_defaults)) {
        ReadingDefaults(s.defaults) { prefs -> store.saveSettings { it.copy(defaults = prefs) } }
    }
    if (langOpen) KSheet({ langOpen = false }, stringResource(R.string.s_language)) {
        KCard {
            languageTags.forEachIndexed { i, tag ->
                KRow(languageLabel(tag), divider = i > 0, onClick = {
                    // AppCompat recreates the activities itself; the framework takes over on API 33+.
                    AppCompatDelegate.setApplicationLocales(
                        if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                    )
                    langOpen = false
                }) { if (tag == currentLang) Text("\u2713", style = KType.title.copy(color = A.accent)) }
            }
        }
    }
}

/** The app-wide reading defaults: what a book with no prefs of its own opens with. */
@Composable
internal fun ReadingDefaults(defaults: ReaderPrefs, onChange: (ReaderPrefs) -> Unit) =
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        // Three drawn pages above the five sliders: "Large print" is one tap that moves four numbers, and the
        // sliders underneath then show what that tap did - so the presets describe the sliders and never own them.
        val current = ReadingPreset.of(defaults)
        if (PresetColumn) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingPreset.entries.forEach { preset ->
                    KPresetTile(
                        label = stringResource(preset.label),
                        selected = current == preset,
                        onClick = { onChange(preset.appliedTo(defaults)) },
                        modifier = Modifier.fillMaxWidth(), wide = true,
                    ) { PageGlyph(preset.fontScale, preset.lineHeight, preset.margins) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadingPreset.entries.forEach { preset ->
                    KPresetTile(
                        label = stringResource(preset.label),
                        selected = current == preset,
                        onClick = { onChange(preset.appliedTo(defaults)) },
                        modifier = Modifier.weight(1f),
                    ) { PageGlyph(preset.fontScale, preset.lineHeight, preset.margins) }
                }
            }
        }
        KControl(stringResource(R.string.s_text_size), stringResource(R.string.value_percent, (defaults.fontScale * 100).toInt())) {
            KSlider(defaults.fontScale.toFloat(), { v -> onChange(defaults.copy(fontScale = v.toDouble())) }, 0.7f..2.5f)
        }
        KControl(stringResource(R.string.s_line_spacing), stringResource(R.string.s_2f).format(defaults.lineHeight)) {
            KSlider(defaults.lineHeight.toFloat(), { v -> onChange(defaults.copy(lineHeight = v.toDouble())) }, 1.0f..2.2f)
        }
        KControl(stringResource(R.string.s_paragraph_spacing), stringResource(R.string.s_1f).format(defaults.paragraphSpacing)) {
            KSlider(defaults.paragraphSpacing.toFloat(), { v -> onChange(defaults.copy(paragraphSpacing = v.toDouble())) }, 0f..2f)
        }
        KControl(stringResource(R.string.s_margins), stringResource(R.string.s_1f).format(defaults.margins)) {
            KSlider(defaults.margins.toFloat(), { v -> onChange(defaults.copy(margins = v.toDouble())) }, 0.5f..2.5f)
        }
        KCard {
            KRow(stringResource(R.string.s_justify_text)) { KSwitch(defaults.justify, { onChange(defaults.copy(justify = it)) }) }
            KRow(stringResource(R.string.s_hyphenation), divider = true) { KSwitch(defaults.hyphens, { onChange(defaults.copy(hyphens = it)) }) }
        }
        Text(stringResource(R.string.s_new_books_open_with_these), style = SmallDim, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    }

/**
 * The two surface styles DRAWN, side by side, over the ambient wash currently chosen: Glass lets the wash and its
 * sheen through a translucent panel; Solid covers it with an opaque bordered card. Both previews call the real
 * recipes, so a change to `glassSurface` or to the Solid card changes the tile with it.
 */
@Composable
private fun SurfacePreview(glassStyle: Boolean) {
    // Full-strength wash inside the Glass tile: 150dp of screen would show the wash at its edges only, and then the
    // one thing that separates the two tiles - colour behind and around the panel - would be invisible at this size.
    Box(Modifier.fillMaxSize().ambientWash(if (glassStyle) 1.4f else SOLID_WASH)) {
        val shape = RoundedCornerShape(13.dp)
        // One card, inset from the tile's edges, because the difference is not the fill - it is that a Glass card
        // floats on colour with a shadow under it and a Solid card sits flat inside a hairline.
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 12.dp).height(42.dp).then(
                if (glassStyle) Modifier.glassSurface(shape, elevation = 8.dp)
                else Modifier.solidSurface(shape, elevation = 4.dp),
            ),
        )
    }
}

/**
 * Off: the reading theme you picked, unchanged. System: light by day and dark by night, drawn as the two pages you
 * actually get. The tile states the rule; the wording under it only names the choice.
 */
@Composable
private fun AutoPreview(system: Boolean, theme: ThemeId) {
    if (system) {
        Row(Modifier.fillMaxSize()) {
            MiniPage(readerUi(ThemeId.LIGHT), Modifier.weight(1f))
            MiniPage(readerUi(ThemeId.DARK), Modifier.weight(1f))
        }
    } else {
        MiniPage(readerUi(theme), Modifier.fillMaxWidth())
    }
}

/**
 * A time of day as a button, because a schedule needs a start and an end and nothing else about it is worth a
 * screen: the system picker knows the reader's clock format, and this row only shows the two choices.
 */
@Composable
private fun TimeChip(label: String, minutes: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier) {
        Text(label, style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        KButton(
            formatMinutes(minutes),
            {
                android.app.TimePickerDialog(
                    ctx,
                    { _, h, m -> onPick(minutesOfDay(h, m)) },
                    minutes / 60, minutes % 60, true,
                ).show()
            },
            Modifier.fillMaxWidth(), style = KButtonStyle.Secondary, small = true,
        )
    }
}

/** A page with three lines of its own ink. */
@Composable
private fun MiniPage(ui: ReaderUi, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().background(ui.page).padding(horizontal = 7.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(3) { i ->
            Box(
                Modifier.fillMaxWidth(if (i == 2) 0.55f else 1f).height(2.dp)
                    .clip(RoundedCornerShape(2.dp)).background(ui.text.copy(alpha = 0.45f)),
            )
            if (i < 2) Spacer(Modifier.height(3.dp))
        }
    }
}

/** Opens a link; when no browser handles it, the tap says so instead of doing nothing. */
private fun openRow(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        android.widget.Toast.makeText(ctx, app.kairn.R.string.s_no_browser, android.widget.Toast.LENGTH_SHORT).show()
    }
}
