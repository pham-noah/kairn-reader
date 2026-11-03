package app.kairn.ui

import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.Color as RColor
import org.readium.r2.navigator.preferences.TextAlign as RTextAlign

/**
 * The reader's settings translated into what Readium wants, with no Activity, no fragment and no
 * resources in the way: every input is a parameter, so the theme and colour decisions can be tested
 * on the JVM instead of by looking at a phone.
 */

/**
 * What a book actually opens in. With the automatic switch off this is just the chosen theme; with it on,
 * the four light-ish choices follow day/night and GRAY stays put.
 */
internal fun effectiveTheme(p: ReaderPrefs, autoSwitch: Boolean, night: Boolean): ThemeId {
    if (!autoSwitch) return p.theme
    // Exhaustive on purpose: an `else` arm would silently fold a future ThemeId into LIGHT/DARK instead of
    // failing to compile.
    return when (p.theme) {
        ThemeId.BLACK -> if (night) ThemeId.BLACK else ThemeId.LIGHT
        ThemeId.SEPIA -> if (night) ThemeId.DARK else ThemeId.SEPIA
        ThemeId.DARK, ThemeId.LIGHT -> if (night) ThemeId.DARK else ThemeId.LIGHT
        ThemeId.GRAY -> ThemeId.GRAY   // a neutral mid tone reads the same day or night: auto-switch leaves it alone
    }
}

/**
 * The preferences handed to the navigator. [twoPageAllowed] is the tablet test, passed in rather than read
 * from resources.
 */
internal fun toEpubPreferences(
    p: ReaderPrefs,
    autoSwitch: Boolean,
    night: Boolean,
    twoPageAllowed: Boolean,
): EpubPreferences {
    val t = effectiveTheme(p, autoSwitch, night)
    // BLACK and GRAY are not Readium themes: they are explicit page/ink colours laid over a base theme.
    // GRAY is charcoal (8.5:1 for body text), so it sits on the DARK base, like BLACK does.
    // A custom page/ink pair from the Display sheet wins over both.
    val bg = p.pageColor?.let { RColor(it) } ?: when (t) {
        ThemeId.BLACK -> RColor(0xFF000000.toInt())
        ThemeId.GRAY -> RColor(0xFF3A4045.toInt())
        else -> null
    }
    val ink = p.inkColor?.let { RColor(it) } ?: when (t) {
        ThemeId.BLACK -> RColor(0xFFDADDE0.toInt())
        ThemeId.GRAY -> RColor(0xFFE4E8EB.toInt())
        else -> null
    }
    return EpubPreferences(
        theme = when (t) {   // exhaustive: BLACK/GRAY carry their colours explicitly above
            ThemeId.LIGHT -> Theme.LIGHT
            ThemeId.SEPIA -> Theme.SEPIA
            ThemeId.DARK, ThemeId.BLACK, ThemeId.GRAY -> Theme.DARK
        },
        backgroundColor = bg,
        textColor = ink,
        fontSize = p.fontScale, lineHeight = p.lineHeight, paragraphSpacing = p.paragraphSpacing,
        pageMargins = p.margins, publisherStyles = false, scroll = p.scroll,
        // R-14: reflowable two-page, tablet only. `spread` is fixed-layout only; setting it here crashes.
        columnCount = if (p.twoPage && twoPageAllowed) ColumnCount.TWO else null,
        textAlign = if (p.justify) RTextAlign.JUSTIFY else RTextAlign.START, hyphens = p.hyphens,
        verticalText = p.vertical,                            // R-15: writing-mode: vertical-rl
        fontFamily = p.fontFamily?.let { FontFamily(it) },   // a CSS family the WebView resolves
    )
}
