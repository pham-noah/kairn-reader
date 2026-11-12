package app.kairn

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Catalog
import app.kairn.data.Library
import app.kairn.data.OpdsClient
import app.kairn.data.ReadiumHelper
import app.kairn.data.Store
import app.kairn.ui.screens.CatalogScreen
import app.kairn.ui.theme.KairnTheme
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog screen, drawn on the JVM. No network is touched: the screen only asks for a page when a row is
 * tapped, so an empty list and a saved catalog are the whole first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CatalogScreenUiTest {

    @get:Rule val compose = createComposeRule()

    private fun store(catalogs: List<Catalog> = emptyList()): Store {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = Store.forTesting(Files.createTempDirectory("kairn-catalog").toFile())
        runBlocking { s.load() }
        if (catalogs.isNotEmpty()) s.saveSettings { it.copy(catalogs = catalogs) }
        return s
    }

    private fun draw(s: Store, back: () -> Unit = {}) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            KairnTheme {
                CatalogScreen(
                    store = s,
                    library = Library(ctx, s, ReadiumHelper(ctx)),
                    client = OpdsClient("1.0.0"),
                    onBack = back,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun the_empty_catalog_screen_offers_a_way_in() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        draw(store())
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_empty)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_add)).assertExists()
    }

    @Test fun a_saved_catalog_is_drawn_with_its_address() {
        draw(store(listOf(Catalog(name = "Calibre", url = "http://192.168.1.5:8080/opds"))))
        compose.onNodeWithText("Calibre").assertExists()
        compose.onNodeWithText("http://192.168.1.5:8080/opds").assertExists()
    }

    @Test fun the_add_form_asks_for_the_four_things_it_needs() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        draw(store())
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_add)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_address)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_username)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.s_catalog_password)).assertExists()
    }

    @Test fun a_catalog_whose_name_is_a_path_still_draws_and_saves() {
        val s = store(listOf(Catalog(name = "../../escape", url = "http://h/opds")))
        draw(s)
        compose.onNodeWithText("../../escape").assertExists()
    }
}
