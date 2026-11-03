package app.kairn.ui

import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Color as RColor

/**
 * Which theme a book actually opens in. The preferences it is handed need Readium's colour type, which
 * cannot be built on the JVM, so those assertions live in ReaderPrefsMappingDeviceTest.
 */
class ReaderPrefsMappingTest {

    @Test fun `automatic switching off leaves the chosen theme alone`() {
        for (t in ThemeId.entries) {
            assertEquals(t, effectiveTheme(ReaderPrefs(theme = t), autoSwitch = false, night = true))
            assertEquals(t, effectiveTheme(ReaderPrefs(theme = t), autoSwitch = false, night = false))
        }
    }

    @Test fun `at night the light themes go dark and gray stays put`() {
        assertEquals(ThemeId.LIGHT, effectiveTheme(ReaderPrefs(theme = ThemeId.BLACK), true, night = false))
        assertEquals(ThemeId.BLACK, effectiveTheme(ReaderPrefs(theme = ThemeId.BLACK), true, night = true))
        assertEquals(ThemeId.SEPIA, effectiveTheme(ReaderPrefs(theme = ThemeId.SEPIA), true, night = false))
        assertEquals(ThemeId.DARK, effectiveTheme(ReaderPrefs(theme = ThemeId.SEPIA), true, night = true))
        assertEquals(ThemeId.LIGHT, effectiveTheme(ReaderPrefs(theme = ThemeId.DARK), true, night = false))
        assertEquals(ThemeId.DARK, effectiveTheme(ReaderPrefs(theme = ThemeId.DARK), true, night = true))
        assertEquals(ThemeId.GRAY, effectiveTheme(ReaderPrefs(theme = ThemeId.GRAY), true, night = true))
        assertEquals(ThemeId.GRAY, effectiveTheme(ReaderPrefs(theme = ThemeId.GRAY), true, night = false))
    }
}
