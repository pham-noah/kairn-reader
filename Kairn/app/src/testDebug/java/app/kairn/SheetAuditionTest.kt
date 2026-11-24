package app.kairn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.kairn.data.BookCast
import app.kairn.data.ReaderPrefs
import app.kairn.ui.DisplaySheet
import app.kairn.ui.TtsSheet
import app.kairn.ui.theme.KairnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two sheets that ask a reader to choose: four voice slots that used to be four numbers, and a page that used
 * to be one book at a time with no way to say "all of them".
 */
@RunWith(RobolectricTestRunner::class)
// Tall on purpose: both sheets are drawn in full here, and a sheet that clips its last button to the bottom of
// a short screen would make the tap land on nothing rather than on the button.
@Config(sdk = [34], qualifiers = "w400dp-h1600dp-xxhdpi")
class SheetAuditionTest {

    @get:Rule val compose = createComposeRule()

    private fun cast(played: (Int) -> Unit) = compose.setContent {
        KairnTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TtsSheet(
                    prefs = ReaderPrefs(),
                    sleepChoice = 0,
                    onRate = {},
                    onSleep = {},
                    onStop = {},
                    cast = BookCast(enabled = true),
                    speakers = listOf("Piranesi"),
                    onCastEnabled = {},
                    onCastVoice = { _, _ -> },
                    onPreview = played,
                )
            }
        }
    }

    @Test fun `the cast sheet says what each of the four voices sounds like`() {
        cast {}
        compose.onNodeWithText("The four voices").assertExists()
        compose.onNodeWithText("Narrator, lower").assertExists()
        compose.onNodeWithText("Second voice").assertExists()
        compose.onNodeWithText("Second, higher").assertExists()
        compose.onNodeWithText("Narrator, higher").assertExists()
    }

    @Test fun `tapping a voice auditions that slot`() {
        var played = -1
        cast { played = it }
        // The label is inside the pad and the pad is the clickable: a touch lands on the parent, as a finger would.
        compose.onNodeWithText("Second, higher").performTouchInput { click() }
        assertEquals(3, played)
    }

    @Test fun `the display sheet offers this page for every book`() {
        var applied = 0
        compose.setContent {
            KairnTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DisplaySheet(ReaderPrefs(), {}, onReset = {}, onApplyToAll = { applied++ })
                }
            }
        }
        // The sheet is 620dp tall and scrolls inside itself, so the button is one flick below the fold; the
        // label sits inside the clickable, so a touch on it lands on the button, as a finger would.
        compose.onNodeWithText("Apply to every book").performScrollTo().performTouchInput { click() }
        assertEquals(1, applied)
    }
}
