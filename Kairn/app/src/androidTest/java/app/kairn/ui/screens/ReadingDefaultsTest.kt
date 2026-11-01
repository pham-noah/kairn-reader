package app.kairn.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kairn.data.ReaderPrefs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings owns the app-wide reading defaults, and the reader opens any book that has no prefs of its own
 * with them. The sheet body is tested on its own: material3 hosts a ModalBottomSheet in its own window,
 * where a Compose test cannot assert on visibility.
 */
@RunWith(AndroidJUnit4::class)
class ReadingDefaultsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_controls_follow_the_stored_defaults() {
        compose.setContent { ReadingDefaults(ReaderPrefs(fontScale = 1.6, margins = 2.0)) {} }
        compose.onNodeWithText("160%").assertIsDisplayed()
        compose.onNodeWithText("2.0").assertIsDisplayed()
        compose.onNodeWithText("Justify text").assertIsDisplayed()
        compose.onNodeWithText("Hyphenation").assertIsDisplayed()
    }

    @Test
    fun the_justify_switch_reports_the_new_prefs() {
        var last: ReaderPrefs? = null
        compose.setContent { ReadingDefaults(ReaderPrefs(justify = false)) { last = it } }
        // Two switches in this component, in order: justify, then hyphenation.
        compose.onAllNodes(isToggleable())[0].performClick()
        assertTrue("justify was not reported", last?.justify == true)
    }
}
