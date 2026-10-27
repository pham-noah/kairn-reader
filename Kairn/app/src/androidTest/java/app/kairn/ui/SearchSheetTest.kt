package app.kairn.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * The search sheet rendered directly: no Activity, no WebView, no IME. adb `input text` puts about two
 * characters into the real field on an emulator, so this is how the result rows get looked at at all.
 */
@RunWith(AndroidJUnit4::class)
class SearchSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun hit(chapter: String) = Locator(
        href = Url(chapter)!!,
        mediaType = MediaType.XHTML,          // non-null parameter
        title = null,
        locations = Locator.Locations(),
        text = Locator.Text(highlight = "whale", before = "the ", after = " swam away"),
    )

    @Test
    fun a_result_names_the_chapter_it_is_in() {
        compose.setContent {
            SearchSheet(
                results = listOf(hit("OEBPS/c1.xhtml")), busy = false, canMore = false,
                onQuery = {}, onMore = {}, onGo = {},
                chapterTitles = mapOf("OEBPS/c1.xhtml" to "Chapter One"),
            )
        }
        compose.onNodeWithText("Chapter One").assertIsDisplayed()
        compose.onNodeWithText("whale", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_result_with_no_known_chapter_still_shows_its_snippet() {
        compose.setContent {
            SearchSheet(
                results = listOf(hit("OEBPS/c9.xhtml")), busy = false, canMore = false,
                onQuery = {}, onMore = {}, onGo = {},
                chapterTitles = mapOf("OEBPS/c1.xhtml" to "Chapter One"),
            )
        }
        compose.onNodeWithText("Chapter One").assertDoesNotExist()
        compose.onNodeWithText("whale", substring = true).assertIsDisplayed()
    }
}
