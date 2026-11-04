package app.kairn.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import app.kairn.data.Update
import app.kairn.ui.theme.KairnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * DS-04: the update sheet is where a release's Markdown used to arrive on screen as Markdown, and where the
 * sheet shrank the moment Download was tapped.
 *
 * Both are held here: the body arrives as text the reader can read (never a `##` or a `|---|`), and the action
 * slot keeps its height when the button is replaced by the progress row.
 */
class UpdateSheetTest {

    @get:Rule val rule = createComposeRule()

    private val release = Update.Release(
        version = "0.9.1",
        url = "https://example.invalid/Kairn-0.9.1.apk",
        size = 6_306_088,
        notes = """
            # Kairn 0.9.1

            The update sheet reads its notes now, and keeps its height while it downloads.

            ## What moved

            | was | is now |
            |---|---|
            | a raw `##` heading | the app's own type |
            | a shrunken sheet | one that stays put |

            - **Bold** and `code` are drawn, not printed.
        """.trimIndent(),
        page = "https://example.invalid/v0.9.1",
    )

    private fun sheet(ui: UpdateUi) = rule.setContent { KairnTheme(glass = false) { UpdateSheet(ui, {}, {}, {}, {}) } }

    @Test fun the_notes_are_readable_text_not_markup() {
        val ui = UpdateUi().apply { release = this@UpdateSheetTest.release }
        sheet(ui)

        rule.onNodeWithText("Kairn 0.9.1 is available").assertExists()
        rule.onNodeWithText("What moved").assertExists()
        rule.onNodeWithText("The update sheet reads its notes now, and keeps its height while it downloads.").assertExists()
        // A heading that kept its hashes, or a table that stayed a pipe line, would show up as these.
        rule.onAllNodesWithText("## ", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("|---", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("- **", substring = true).assertCountEquals(0)
    }

    @Test fun the_sheet_does_not_shrink_when_the_download_starts() {
        val ui = UpdateUi().apply { release = this@UpdateSheetTest.release }
        sheet(ui)

        // The sheet is anchored to the bottom edge, so a change in its height moves its top - which is the
        // title, and exactly what the reader saw jump when Download was tapped.
        val idleTop = rule.onNodeWithText("Kairn 0.9.1 is available").getUnclippedBoundsInRoot().top

        ui.downloading = true
        rule.waitForIdle()
        val downloadingTop = rule.onNodeWithText("Kairn 0.9.1 is available").getUnclippedBoundsInRoot().top

        assertEquals("the sheet must not resize under the reader's thumb", idleTop.value, downloadingTop.value, 1f)
    }
}
