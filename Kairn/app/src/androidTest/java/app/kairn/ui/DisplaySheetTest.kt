package app.kairn.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kairn.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Display sheet writes per-book reading settings (R-10). Every control in it is a copy() of the prefs
 * it was handed, so the sheet is testable without a navigator: compose it, tap, assert what came out.
 */
@RunWith(AndroidJUnit4::class)
class DisplaySheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosing_two_pages_emits_a_pref_with_two_pages() {
        var emitted: ReaderPrefs? = null
        compose.setContent {
            DisplaySheet(prefs = ReaderPrefs(twoPage = false), onChange = { emitted = it }, showColumns = true)
        }
        compose.onNodeWithText("Two pages").performClick()
        assertEquals(true, emitted?.twoPage)
    }

    @Test
    fun the_segments_do_not_touch_anything_else() {
        var emitted: ReaderPrefs? = null
        val start = ReaderPrefs(fontScale = 1.3, theme = app.kairn.data.ThemeId.SEPIA)
        compose.setContent { DisplaySheet(prefs = start, onChange = { emitted = it }, showColumns = true) }
        compose.onNodeWithText("Two pages").performClick()
        assertEquals(1.3, emitted?.fontScale ?: 0.0, 0.001)
        assertEquals(app.kairn.data.ThemeId.SEPIA, emitted?.theme)
    }

    @Test
    fun reset_reaches_the_defaults_callback() {
        var reset = false
        compose.setContent {
            DisplaySheet(prefs = ReaderPrefs(), onChange = {}, showColumns = false, onReset = { reset = true })
        }
        compose.onNodeWithText("Reset this book to your defaults").performScrollTo().performClick()
        assertTrue("reset never fired", reset)
    }

    @Test
    fun the_follow_system_brightness_action_only_exists_once_a_level_was_set() {
        compose.setContent {
            DisplaySheet(prefs = ReaderPrefs(brightness = -1f), onChange = {}, showColumns = false)
        }
        compose.onNodeWithText("Follow the system brightness").assertDoesNotExist()
    }

    @Test
    fun columns_are_not_offered_on_a_phone() {
        // showColumns stays false on a phone; the control must then be absent rather than hidden-but-there.
        compose.setContent {
            DisplaySheet(prefs = ReaderPrefs(), onChange = {}, showColumns = false)
        }
        compose.onNodeWithText("Columns").assertDoesNotExist()
    }

    @Test
    fun nothing_is_emitted_before_a_tap() {
        var emitted: ReaderPrefs? = null
        compose.setContent { DisplaySheet(prefs = ReaderPrefs(), onChange = { emitted = it }, showColumns = true) }
        assertNull(emitted)
    }
}
