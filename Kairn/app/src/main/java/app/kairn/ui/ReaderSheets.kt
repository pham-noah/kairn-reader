@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.kairn.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Icon(icon, label, tint = ui.chromeText, modifier = Modifier.size(22.dp)); Text(label, color = ui.chromeText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }

private val readerThemes = listOf(ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.GRAY, ThemeId.DARK, ThemeId.BLACK)   // constant: not rebuilt per recomposition; ordered by page tone
private val pageModes = listOf("Pages", "Scroll")
private val columnModes = listOf("One page", "Two pages")

@Composable
internal fun DisplaySheet(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, showColumns: Boolean = true) = Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        readerThemes.forEach { t -> KThemeSwatch(t, prefs.theme == t, { onChange(prefs.copy(theme = t)) }) }
    }
    Spacer(Modifier.height(16.dp))
    KSegmented(pageModes, if (prefs.scroll) 1 else 0, { onChange(prefs.copy(scroll = it == 1)) })
    // A phone has no room for two readable columns, so the choice is not offered there, and a value
    // stored on a tablet is not honoured on a phone either (see ReaderActivity.isTablet).
    if (showColumns) {
        KControl("Columns", if (prefs.twoPage) "Two pages" else "One page") {
            KSegmented(columnModes, if (prefs.twoPage) 1 else 0, { onChange(prefs.copy(twoPage = it == 1)) })
        }
        Text("Two pages suits a tablet or a wide screen.", style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
    KControl("Text size", "${(prefs.fontScale * 100).toInt()}%") { KSlider(prefs.fontScale.toFloat(), { onChange(prefs.copy(fontScale = it.toDouble())) }, 0.7f..2.5f) }
    KControl("Line spacing", "%.2f".format(prefs.lineHeight)) { KSlider(prefs.lineHeight.toFloat(), { onChange(prefs.copy(lineHeight = it.toDouble())) }, 1.0f..2.2f) }
    KControl("Paragraph spacing", "%.1f".format(prefs.paragraphSpacing)) { KSlider(prefs.paragraphSpacing.toFloat(), { onChange(prefs.copy(paragraphSpacing = it.toDouble())) }, 0f..2f) }
    KControl("Margins", "%.1f".format(prefs.margins)) { KSlider(prefs.margins.toFloat(), { onChange(prefs.copy(margins = it.toDouble())) }, 0.5f..2.5f) }
    KControl("Warm light", "${(prefs.warmth * 100).toInt()}%") { KSlider(prefs.warmth, { onChange(prefs.copy(warmth = it)) }, accent = K.amber) }
    KControl("Brightness, this app only", if (prefs.brightness < 0) "System" else "${(prefs.brightness * 100).toInt()}%") { KSlider(if (prefs.brightness < 0) 0.5f else prefs.brightness, { onChange(prefs.copy(brightness = it.coerceAtLeast(0.05f))) }, 0.05f..1f) }
    Spacer(Modifier.height(8.dp))
    KCard {
        KRow("Justify text") { KSwitch(prefs.justify, { onChange(prefs.copy(justify = it)) }) }
        KRow("Hyphenation", divider = true) { KSwitch(prefs.hyphens, { onChange(prefs.copy(hyphens = it)) }) }
    }
    Text("These settings are remembered for this book.", style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, top = 12.dp))
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
            Text(l.title ?: "Untitled", style = KType.title, modifier = Modifier.fillMaxWidth().clickable { pub?.locatorFromLink(l)?.let(onGo) }.padding(start = (16 + d * 16).dp, end = 16.dp, top = 15.dp, bottom = 15.dp))
        }
    } }
}

@Composable
internal fun SearchSheet(results: List<Locator>, onQuery: (String) -> Unit, onGo: (Locator) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.heightIn(max = 560.dp).imePadding()) {
        KField(q, { q = it; onQuery(it) }, placeholder = "Search in this book", leading = Icons.Default.Search)
        Spacer(Modifier.height(8.dp))
        if (q.length >= 3 && results.isEmpty()) Text("No matches yet.", style = KType.small, modifier = Modifier.padding(start = 4.dp))
        LazyColumn { items(results.size) { i ->
            val l = results[i]
            Text(buildAnnotatedString {
                append("…" + l.text.before?.takeLast(30).orEmpty())
                withStyle(SpanStyle(background = K.mint.copy(alpha = .3f))) { append(l.text.highlight.orEmpty()) }
                append(l.text.after?.take(40).orEmpty() + "…")
            }, color = K.txt, modifier = Modifier.fillMaxWidth().clickable { onGo(l) }.padding(vertical = 12.dp, horizontal = 4.dp))
        } }
    }
}
