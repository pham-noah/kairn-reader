package app.kairn

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import app.kairn.data.BookCast
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.data.ReadingStats
import app.kairn.data.Store
import app.kairn.ui.ContentsSheet
import app.kairn.ui.DisplaySheet
import app.kairn.ui.NoteEditor
import app.kairn.ui.NoteTarget
import app.kairn.ui.SearchSheet
import app.kairn.ui.TtsSheet
import app.kairn.ui.screens.AnnotationsContent
import app.kairn.ui.screens.BackupScreen
import app.kairn.ui.screens.BackupUi
import app.kairn.ui.screens.CollectionsScreen
import app.kairn.ui.screens.OnboardingScreen
import app.kairn.ui.screens.StatsContent
import app.kairn.ui.screens.StatsSheet
import app.kairn.ui.screens.UpdateContent
import app.kairn.ui.screens.UpdateUi
import app.kairn.data.Update
import app.kairn.ui.theme.KairnTheme
import kotlinx.coroutines.runBlocking
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * The screens and sheet bodies that no other test draws.
 *
 * Every reader sheet is composed inside `KSheet`, which is a `ModalBottomSheet` and therefore a Dialog: the
 * sheet bodies had never been drawn on the JVM at all, and neither had onboarding, collections or backup. A
 * composition that throws while it lays out is exactly what these catch — settings and add-books already had
 * this treatment (ScreensUiTest), these did not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class WholeAppUiTest {

    @get:Rule val compose = createComposeRule()

    private fun store(): Store {
        val st = Store.forTesting(Files.createTempDirectory("kairn-whole").toFile())
        runBlocking { st.load() }
        return st
    }

    /**
     * Draws and asserts *something* composed. `onRoot()` cannot be used: a body that still contains its own
     * `KSheet` adds a second (dialog) root, and `onRoot` insists on exactly one.
     */
    private fun draw(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { KairnTheme { content() } }
        compose.waitForIdle()
        assertTrue("nothing composed", compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().isNotEmpty())
    }

    /** A mark as the annotations list can actually receive one: hostile text, an unparseable locator. */
    private fun mark(i: Int, quote: String = "a line", note: String = "") =
        Mark("m$i", if (i % 3 == 0) "bookmark" else "highlight", 0xFF69D3B4.toInt(), quote, note, "{}", 0L)

    // ---- the three screens nobody drew ----

    @Test fun onboarding_draws_its_first_step() {
        draw { OnboardingScreen(store()) {} }
    }

    @Test fun collections_draws_with_no_books_and_no_collections() {
        draw { CollectionsScreen(store()) {} }
    }

    @Test fun backup_draws_before_anything_has_been_backed_up() {
        draw {
            BackupScreen(BackupUi(), store(), onBack = {}, onBackupNow = {}, onRestoreFromFile = {},
                autoFolderName = null, onPickAutoFolder = {}, onAutoBackupOff = {})
        }
    }

    // ---- the sheet bodies, which a ModalBottomSheet hid from every test ----

    @Test fun the_stats_record_draws_for_a_reader_with_no_minutes_at_all() {
        draw { StatsContent(ReadingStats(), 0) }
        compose.onNodeWithText("Today").assertExists()
        assertTrue("both zero stat boxes should read 0 min", compose.onAllNodesWithText("0 min").fetchSemanticsNodes().size == 2)
        compose.onNodeWithText("0 d").assertExists()            // and the streak
    }

    @Test fun the_stats_record_draws_a_full_week() {
        val days = (0L until 7L).associate { java.time.LocalDate.now().minusDays(it).toString() to (it.toInt() * 37 + 1) }
        draw { StatsContent(ReadingStats(days = days, finished = setOf("a", "b")), 2) }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("1 min").assertExists()          // today: the newest day of the week
        compose.onNodeWithText("All time: 13 h 4 min across 2 finished books").assertExists()
    }

    @Test fun the_stats_sheet_still_wraps_its_body() {
        // The sheet is a dialog window: it composes, but only its labels are assertable.
        draw { StatsSheet(ReadingStats(), 0) {} }
        compose.onNodeWithText("Today").assertExists()
    }

    @Test fun the_update_record_draws_in_every_state_it_can_be_in() {
        // "Checking" is one state; "up to date" and a failed check are another; then a found release with the
        // download button, the progress row, and the install button.
        val found = Update.Release(version = "9.9.9", url = "https://example.com/k.apk", size = 33_000_000,
            notes = "# Fixes\n".repeat(400), page = "https://example.com")
        val states = listOf(
            UpdateUi().apply { checking = true },
            UpdateUi(),
            UpdateUi().apply { message = "The check failed." },
            UpdateUi().apply { release = found },
            UpdateUi().apply { release = found; downloading = true; progress = 0.42f },
            UpdateUi().apply { release = found; apk = java.io.File("k.apk") },
        )
        // One setContent per rule: the states share a composition and are stepped through by recomposition.
        val ui = states.first()
        draw { UpdateContent(ui, {}, {}, {}) }
        compose.onNodeWithText("Checking…").assertExists()
        states.drop(1).forEach { next ->
            compose.runOnIdle {
                ui.checking = next.checking; ui.message = next.message; ui.release = next.release
                ui.downloading = next.downloading; ui.progress = next.progress; ui.apk = next.apk
            }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Install").assertExists()        // the last state drew
    }

    @Test fun annotations_draw_with_nothing_in_them() {
        draw { AnnotationsContent("Piranesi", emptyList(), {}, {}, {}, {}) }
    }

    @Test fun annotations_draw_with_a_huge_list_and_hostile_text() {
        val marks = (0 until 500).map { mark(it, quote = "x".repeat(20_000), note = if (it % 5 == 0) "y".repeat(20_000) else "") }
        draw { AnnotationsContent("Piranesi", marks, {}, {}, {}, {}) }
    }

    @Test fun the_note_editor_draws_empty_and_with_a_quote() {
        draw { NoteEditor(NoteTarget("", "{}"), "") {} }
    }

    @Test fun the_display_sheet_draws_for_both_column_modes() {
        draw { DisplaySheet(ReaderPrefs(), {}, showColumns = true, onReset = {}) }
    }

    @Test fun the_read_aloud_sheet_draws_with_the_timer_on_the_chapter() {
        draw {
            TtsSheet(ReaderPrefs(), sleepChoice = -1, onRate = {}, onSleep = {}, onStop = {},
                cast = BookCast(), speakers = List(60) { "Speaker $it" }, onCastEnabled = {}, onCastVoice = { _, _ -> })
        }
    }

    @Test fun the_contents_sheet_draws_before_the_publication_arrives() {
        draw { ContentsSheet(null, null) {} }
    }

    @Test fun the_search_sheet_draws_empty_and_with_results() {
        draw {
            SearchSheet(emptyList(), busy = false, canMore = false, onQuery = {}, onMore = {}, onGo = {},
                chapterTitles = emptyMap(), chapterOnly = false, onChapterOnly = {}, showChapterFilter = false)
        }
    }

    @Test fun the_search_sheet_draws_results_with_hostile_chapter_titles() {
        val hits = (0 until 40).map {
            Locator(href = Url("OEBPS/c$it.xhtml")!!, mediaType = MediaType.XHTML, title = null,
                locations = Locator.Locations(), text = Locator.Text(highlight = "whale".repeat(2000)))
        }
        draw {
            SearchSheet(hits, busy = false, canMore = true, onQuery = {}, onMore = {}, onGo = {},
                chapterTitles = hits.associate { it.href.toString() to "Chapter ".repeat(800) },
                chapterOnly = false, onChapterOnly = {}, showChapterFilter = true)
        }
    }
}
