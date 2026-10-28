@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.kairn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.data.*
import app.kairn.ui.kit.*
import app.kairn.ui.theme.*
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/*
 * The reader's sheets. They are stateless: everything they show comes in as a parameter and every
 * action goes out as a callback, so they can be previewed or tested on their own.
 */

@Composable
internal fun RowScope.ReaderTool(icon: ImageVector, label: String, ui: ReaderUi, onClick: () -> Unit) =
    Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Icon(icon, label, tint = ui.chromeText, modifier = Modifier.size(22.dp)); Text(label, color = ui.chromeText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }

private val readerThemes = listOf(ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.GRAY, ThemeId.DARK, ThemeId.BLACK)   // constant: not rebuilt per recomposition; ordered by page tone
private val pageModes = listOf(R.string.s_pages, R.string.s_scroll)
// A custom page and ink: ten tones and five inks make forty readable pairs without a colour wheel.
private val pageTones = listOf(0xFFF6F5F1, 0xFFEDE7DC, 0xFFD8D2C6, 0xFFBFC4C0, 0xFF8E959B,
                               0xFF6A737A, 0xFF4A5158, 0xFF3A4045, 0xFF24282C, 0xFF101214).map(Long::toInt)
private val inkTones = listOf(0xFF14171A, 0xFF3B4147, 0xFF6A7178, 0xFFD8DEE3, 0xFFF4F6F8).map(Long::toInt)

@Composable
private fun ColourDot(argb: Int, selected: Boolean, onClick: () -> Unit) = Box(
    Modifier.heightIn(min = 48.dp).width(30.dp).clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected },
    contentAlignment = Alignment.Center,
) {
    Box(
        Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(argb))
            .border(if (selected) 2.dp else 1.dp, if (selected) K.mint else Color(0x66FFFFFF), androidx.compose.foundation.shape.CircleShape),
    )
}
private val columnModes = listOf(R.string.s_one_page, R.string.s_two_pages)

/** Reading aloud: the speed, and the way out. Pause and resume stay on the bar, one tap from the page. */
@Composable
internal fun TtsSheet(prefs: ReaderPrefs, onRate: (Float) -> Unit, onStop: () -> Unit) {
    KControl(stringResource(R.string.s_speech_rate), stringResource(R.string.s_speech_rate_value, prefs.speechRate)) {
        KSlider(prefs.speechRate, onRate, 0.6f..2f)
    }
    Text(stringResource(R.string.s_speech_rate_hint), style = KType.small.copy(color = K.dim),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp))
    KButton(stringResource(R.string.s_stop_reading), onStop, style = KButtonStyle.Secondary)
}

/** What a selection turned into when "Note" was tapped: the quoted text and its Locator JSON. */
class NoteTarget(val quote: String, val locator: String)

/** The note editor. The quoted text stays visible so it is obvious what the note belongs to. */
@Composable
internal fun NoteEditor(target: NoteTarget, initial: String = "", onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    if (target.quote.isNotBlank()) {
        Text(
            "“" + target.quote.take(180) + if (target.quote.length > 180) "…" else "",
            style = KType.quote.copy(color = K.mut),
            maxLines = 4,
            modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
        )
    }
    KField(text, { text = it }, label = stringResource(R.string.s_your_note), singleLine = false, minLines = 3)
    Spacer(Modifier.height(16.dp))
    KButton(stringResource(R.string.s_save_note), { onSave(text.trim()) }, enabled = text.isNotBlank())
}

// Readium ships no fonts of its own, so these are the families Android's WebView can resolve.
private val fontModes = listOf(R.string.s_font_default, R.string.s_font_serif, R.string.s_font_sans, R.string.s_font_mono)
private val fontValues: List<String?> = listOf(null, "serif", "sans-serif", "monospace")
private fun fontIndexOf(family: String?): Int = fontValues.indexOf(family).takeIf { it >= 0 } ?: 0

@Composable
internal fun DisplaySheet(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, showColumns: Boolean = true, onReset: (() -> Unit)? = null) = Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        readerThemes.forEach { t -> KThemeSwatch(t, prefs.theme == t, { onChange(prefs.copy(theme = t)) }) }
    }
    // Sits on top of the theme: the swatch row still decides the chrome, these two decide the page.
    Text(stringResource(R.string.s_custom_colours), style = KType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
    Text(stringResource(R.string.s_custom_colours_hint), style = KType.small.copy(color = K.dim))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        pageTones.forEach { c -> ColourDot(c, prefs.pageColor == c, { onChange(prefs.copy(pageColor = if (prefs.pageColor == c) null else c)) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        inkTones.forEach { c -> ColourDot(c, prefs.inkColor == c, { onChange(prefs.copy(inkColor = if (prefs.inkColor == c) null else c)) }) }
    }
    if (prefs.pageColor != null || prefs.inkColor != null) {
        KButton(stringResource(R.string.s_theme_colours), { onChange(prefs.copy(pageColor = null, inkColor = null)) },
            style = KButtonStyle.Ghost, small = true)
    }
    Spacer(Modifier.height(16.dp))
    KSegmented(pageModes.map { stringResource(it) }, if (prefs.scroll) 1 else 0, { onChange(prefs.copy(scroll = it == 1)) })
    if (prefs.scroll) {
        KControl(stringResource(R.string.s_scroll_speed), stringResource(R.string.s_dp_per_second, prefs.scrollSpeed.toInt())) {
            KSlider(prefs.scrollSpeed, { onChange(prefs.copy(scrollSpeed = it)) }, 20f..200f)
        }
    }
    // A phone has no room for two readable columns, so the choice is not offered there, and a value
    // stored on a tablet is not honoured on a phone either (see ReaderActivity.isTablet).
    if (showColumns) {
        KControl(stringResource(R.string.s_columns), if (prefs.twoPage) stringResource(R.string.s_two_pages) else stringResource(R.string.s_one_page)) {
            KSegmented(columnModes.map { stringResource(it) }, if (prefs.twoPage) 1 else 0, { onChange(prefs.copy(twoPage = it == 1)) })
        }
        Text(stringResource(R.string.s_two_pages_suits_a_tablet_or_a_wide_scree), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
    KControl(stringResource(R.string.s_typeface), stringResource(fontModes[fontIndexOf(prefs.fontFamily)])) {
        KSegmented(fontModes.map { stringResource(it) }, fontIndexOf(prefs.fontFamily), {
            onChange(prefs.copy(fontFamily = fontValues[it]))
        })
    }
    KControl(stringResource(R.string.s_text_size), stringResource(R.string.value_percent, (prefs.fontScale * 100).toInt())) { KSlider(prefs.fontScale.toFloat(), { onChange(prefs.copy(fontScale = it.toDouble())) }, 0.7f..2.5f) }
    KControl(stringResource(R.string.s_line_spacing), stringResource(R.string.s_2f).format(prefs.lineHeight)) { KSlider(prefs.lineHeight.toFloat(), { onChange(prefs.copy(lineHeight = it.toDouble())) }, 1.0f..2.2f) }
    KControl(stringResource(R.string.s_paragraph_spacing), stringResource(R.string.s_1f).format(prefs.paragraphSpacing)) { KSlider(prefs.paragraphSpacing.toFloat(), { onChange(prefs.copy(paragraphSpacing = it.toDouble())) }, 0f..2f) }
    KControl(stringResource(R.string.s_margins), stringResource(R.string.s_1f).format(prefs.margins)) { KSlider(prefs.margins.toFloat(), { onChange(prefs.copy(margins = it.toDouble())) }, 0.5f..2.5f) }
    KControl(stringResource(R.string.s_warm_light), stringResource(R.string.value_percent, (prefs.warmth * 100).toInt())) { KSlider(prefs.warmth, { onChange(prefs.copy(warmth = it)) }, accent = K.amber) }
    KControl(stringResource(R.string.s_brightness_this_app_only), if (prefs.brightness < 0) stringResource(R.string.s_system) else stringResource(R.string.value_percent, (prefs.brightness * 100).toInt())) { KSlider(if (prefs.brightness < 0) 0.5f else prefs.brightness, { onChange(prefs.copy(brightness = it.coerceAtLeast(0.05f))) }, 0.05f..1f) }
    // The slider can only set an explicit level, so without this a book could never go back to following
    // the system brightness once the slider had been touched.
    if (prefs.brightness >= 0f) {
        KButton(stringResource(R.string.s_follow_the_system_brightness), { onChange(prefs.copy(brightness = -1f)) }, style = KButtonStyle.Ghost, small = true)
    }
    Spacer(Modifier.height(8.dp))
    KCard {
        KRow(stringResource(R.string.s_justify_text)) { KSwitch(prefs.justify, { onChange(prefs.copy(justify = it)) }) }
        KRow(stringResource(R.string.s_hyphenation), divider = true) { KSwitch(prefs.hyphens, { onChange(prefs.copy(hyphens = it)) }) }
    }
    Text(stringResource(R.string.s_these_settings_are_remembered_for_this_b), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    // Every slider here is sticky, so a book that has been fiddled with needs a way back to the app defaults.
    if (onReset != null) KButton(stringResource(R.string.s_reset_this_book_to_your_defaults), onReset, Modifier.padding(top = 10.dp), style = KButtonStyle.Ghost, small = true)
}

@Composable
internal fun ContentsSheet(pub: Publication?, onGo: (Locator) -> Unit) {
    val flat = remember(pub) {
        val out = mutableListOf<Pair<Link, Int>>()
        fun walk(ls: List<Link>, d: Int) {
            ls.forEach { out += it to d; walk(it.children, d + 1) }
        }
        walk(pub?.tableOfContents.orEmpty(), 0)
        out
    }
    KCard { LazyColumn(Modifier.heightIn(max = 520.dp)) {
        items(flat.size) { i ->
            val (l, d) = flat[i]
            if (i > 0) HorizontalDivider(color = K.line)
            Text(l.title ?: stringResource(R.string.s_untitled), style = KType.title, modifier = Modifier.fillMaxWidth().clickable { pub?.locatorFromLink(l)?.let(onGo) }.padding(start = (16 + d * 16).dp, end = 16.dp, top = 15.dp, bottom = 15.dp))
        }
    } }
}

@Composable
internal fun SearchSheet(
    results: List<Locator>, busy: Boolean, canMore: Boolean,
    onQuery: (String) -> Unit, onMore: () -> Unit, onGo: (Locator) -> Unit,
    chapterTitles: Map<String, String> = emptyMap(),
    chapterOnly: Boolean = false,
    onChapterOnly: (Boolean) -> Unit = {},
    showChapterFilter: Boolean = false,
) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.heightIn(max = 560.dp).imePadding()) {
        KField(q, { q = it; onQuery(it) }, placeholder = stringResource(R.string.s_search_in_this_book), leading = Icons.Default.Search)
        if (showChapterFilter) Row(Modifier.padding(top = 8.dp)) {
            KChip(stringResource(R.string.s_this_chapter_only), chapterOnly, { onChapterOnly(!chapterOnly) })
        }
        Spacer(Modifier.height(8.dp))
        // Three states, in order: working, nothing found, a result count. "No matches" over a running
        // search reads as a failed search.
        val note = when {
            busy -> stringResource(R.string.s_searching)
            q.length < 3 -> stringResource(R.string.s_type_three_letters)
            results.isEmpty() -> stringResource(R.string.s_no_matches)
            else -> pluralStringResource(R.plurals.matches_found, results.size, results.size)
        }
        Text(note, style = KType.small, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        LazyColumn { items(results.size) { i ->
            val l = results[i]
            // Which chapter a hit is in: a snippet alone does not tell you where you would land.
            val here = chapterTitles[l.href.toString()] ?: chapterTitles[l.href.toString().substringAfterLast('/')]
            Column(Modifier.fillMaxWidth().clickable { onGo(l) }.padding(vertical = 12.dp, horizontal = 4.dp)) {
            if (here != null) Text(here, style = KType.label.copy(color = K.dim), maxLines = 1)
            Text(buildAnnotatedString {
                append("…" + l.text.before?.takeLast(30).orEmpty())
                withStyle(SpanStyle(background = K.mint.copy(alpha = .3f))) { append(l.text.highlight.orEmpty()) }
                append(l.text.after?.take(40).orEmpty() + "…")
            }, color = K.txt)
            }
        }
        // A cap without a way past it reads as "that is all there is".
        if (canMore && !busy) item {
            KButton(stringResource(R.string.s_show_more_results), onMore, style = KButtonStyle.Secondary, small = true, modifier = Modifier.padding(top = 10.dp))
        }
        }
    }
}
