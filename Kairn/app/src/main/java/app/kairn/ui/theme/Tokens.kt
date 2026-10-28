package app.kairn.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.kairn.data.ThemeId

/** Design tokens: the app's base colours, sizes and accents. */
object K {
    val bg = Color(0xFF0E1114); val surf = Color(0xFF161A1E); val surf2 = Color(0xFF1E2328)
    val line = Color(0xFF2A3036); val txt = Color(0xFFEEF1F3); val mut = Color(0xFFA2ACB3)
    val dim = Color(0xFF838D94)
    val mint = Color(0xFF7EE0C3); val mintInk = Color(0xFF0B2A22)
    val amber = Color(0xFFE8B45C); val rose = Color(0xFFE68B9B); val sky = Color(0xFF80B5EA)
    val violet = Color(0xFFC3A6F0); val sand = Color(0xFFE8D6A6)
    val danger = Color(0xFFF0848A)
    val track = Color(0xFF2F363D); val switchOff = Color(0xFF2C333A); val grab = Color(0xFF3B434A)
    val toastBg = Color(0xFFE9EEF0); val toastInk = Color(0xFF12171A)
    /** Highlight palette, in the order the selection toolbar shows it. The label travels WITH the
     *  colour, so a new colour cannot be mislabelled. */
    val highlightColors = listOf(
    "Amber" to amber, "Mint" to mint, "Rose" to rose, "Blue" to sky, "Violet" to violet, "Sand" to sand,
)
}

/** Spacing + shape constants (dp values as plain ints so they stay out of Compose imports). */
object Dim {
    const val screenPad = 20
    const val card = 20          // .card radius
    const val sheet = 26         // sheet top radius
    const val field = 14
    const val btn = 50
    const val btnSmall = 40
    const val tap = 48           // minimum touch target
}

/** The reader theme token set: page, text, muted, chrome and chromeText colours, hairline, accent, and whether the theme is light. */
data class ReaderUi(val page: Color, val text: Color, val muted: Color, val chrome: Color, val chromeText: Color, val line: Color, val accent: Color, val light: Boolean)

/** One instance per theme, built once. */
fun readerUi(t: ThemeId): ReaderUi = READER_UI.getValue(t)

/**
 * The theme's colours with a custom page/ink pair laid over them. `light` follows the page's luminance, so
 * badges and swatch rings stay legible whichever pair the reader picked.
 */
fun readerUi(t: ThemeId, page: Int?, ink: Int?): ReaderUi {
    val base = readerUi(t)
    if (page == null && ink == null) return base
    val p = Color((page ?: base.page.toArgb()) or 0xFF000000.toInt())
    val k = Color((ink ?: base.text.toArgb()) or 0xFF000000.toInt())
    val lum = 0.2126 * p.red + 0.7152 * p.green + 0.0722 * p.blue
    return base.copy(page = p, text = k, light = lum > 0.5f, chrome = p.copy(alpha = 0.96f), chromeText = k)
}

private val READER_UI: Map<ThemeId, ReaderUi> = ThemeId.entries.associateWith { buildReaderUi(it) }

private fun buildReaderUi(t: ThemeId): ReaderUi = when (t) {
    ThemeId.DARK -> ReaderUi(Color(0xFF111418), Color(0xFFD9DDE0), Color(0xFF8C969D), Color(0xF5111418), Color(0xFFE6EAED), Color(0x1FFFFFFF), K.mint, false)
    ThemeId.LIGHT -> ReaderUi(Color(0xFFF6F5F1), Color(0xFF1D1E20), Color(0xFF6A6C70), Color(0xF7F6F5F1), Color(0xFF1D1E20), Color(0x24000000), Color(0xFF14876A), true)
    ThemeId.SEPIA -> ReaderUi(Color(0xFFF1E4CF), Color(0xFF3A2E21), Color(0xFF6E5F4C), Color(0xF7F1E4CF), Color(0xFF3A2E21), Color(0x2E3C280A), Color(0xFF14876A), true)
    ThemeId.BLACK -> ReaderUi(Color(0xFF000000), Color(0xFFC9CED2), Color(0xFF7F898F), Color(0xF7000000), Color(0xFFE6EAED), Color(0x1FFFFFFF), K.mint, false)
    // Charcoal, not mid grey: 8.5:1 for body text and 4.8:1 for the muted chrome, both measured on #3A4045.
    ThemeId.GRAY -> ReaderUi(Color(0xFF3A4045), Color(0xFFE4E8EB), Color(0xFFA8B0B6), Color(0xF53A4045), Color(0xFFE4E8EB), Color(0x1FFFFFFF), K.mint, false)
}

/** Generated cover palettes for books with no embedded cover. */
val coverPalettes = listOf(
    Color(0xFF2A4A5F) to Color(0xFF14232E), Color(0xFF6A4A2A) to Color(0xFF392612),
    Color(0xFF4F6A3A) to Color(0xFF2A3A1F), Color(0xFF5A3A5F) to Color(0xFF2B1B2E),
    Color(0xFF6A3A3A) to Color(0xFF331B1B), Color(0xFF3A5F5A) to Color(0xFF1B2E2B),
    Color(0xFF5F5A3A) to Color(0xFF2E2B1B), Color(0xFF3A4A6A) to Color(0xFF1B2333),
)
