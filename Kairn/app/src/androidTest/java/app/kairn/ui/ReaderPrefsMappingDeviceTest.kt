package app.kairn.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Color as RColor

/**
 * The preferences handed to Readium, on a device because its colour type cannot be built on the JVM.
 * The theme arithmetic it shares with the JVM suite is in ReaderPrefsMappingTest.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPrefsMappingDeviceTest {

    @Test fun blackIsADarkBaseWithExplicitPageAndInk() {
        val e = toEpubPreferences(ReaderPrefs(theme = ThemeId.BLACK), autoSwitch = false, night = false, twoPageAllowed = false)
        assertEquals(Theme.DARK, e.theme)
        assertEquals(RColor(0xFF000000.toInt()), e.backgroundColor)
        assertEquals(RColor(0xFFDADDE0.toInt()), e.textColor)
    }

    @Test fun aCustomPairWinsOverTheThemesOwn() {
        val p = ReaderPrefs(theme = ThemeId.BLACK, pageColor = 0xFF102030.toInt(), inkColor = 0xFFF0E0D0.toInt())
        val e = toEpubPreferences(p, autoSwitch = false, night = false, twoPageAllowed = false)
        assertEquals(RColor(0xFF102030.toInt()), e.backgroundColor)
        assertEquals(RColor(0xFFF0E0D0.toInt()), e.textColor)
    }

    @Test fun lightAndSepiaCarryNoExplicitColours() {
        for (t in listOf(ThemeId.LIGHT, ThemeId.SEPIA)) {
            val e = toEpubPreferences(ReaderPrefs(theme = t), autoSwitch = false, night = false, twoPageAllowed = true)
            assertNull(e.backgroundColor)
            assertNull(e.textColor)
        }
    }

    @Test fun twoPagesNeedBothTheSettingAndAWideScreen() {
        val p = ReaderPrefs(twoPage = true)
        assertEquals(ColumnCount.TWO, toEpubPreferences(p, false, false, twoPageAllowed = true).columnCount)
        assertNull(toEpubPreferences(p, false, false, twoPageAllowed = false).columnCount)
        assertNull(toEpubPreferences(ReaderPrefs(twoPage = false), false, false, twoPageAllowed = true).columnCount)
    }

    @Test fun typeSettingsAndLayoutChoicesAreCarriedThrough() {
        val p = ReaderPrefs(fontScale = 1.3, lineHeight = 1.8, paragraphSpacing = 0.8, margins = 1.4,
            justify = true, hyphens = false, vertical = true, scroll = true, fontFamily = "Literata")
        val e = toEpubPreferences(p, autoSwitch = false, night = false, twoPageAllowed = false)
        assertEquals(1.3, e.fontSize!!, 0.0001)
        assertEquals(1.8, e.lineHeight!!, 0.0001)
        assertEquals(1.4, e.pageMargins!!, 0.0001)
        assertEquals(TextAlign.JUSTIFY, e.textAlign)
        assertEquals(false, e.hyphens)
        assertTrue(e.verticalText == true)
        assertTrue(e.scroll == true)
        assertEquals("Literata", e.fontFamily?.name)
        assertEquals(false, e.publisherStyles)
    }
}
