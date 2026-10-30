package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two things that must never cost a reader their library: a settings file written by a different build, and a
 * settings file that is not JSON at all. The first must lose only the bad entry, the second must leave a copy
 * behind instead of silently running on defaults and overwriting it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsResilienceDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp
    private val dir: File get() = app.store.root
    private val file: File get() = File(dir, "settings.json")
    private val bak: File get() = File(dir, "settings.json.bak")

    @org.junit.Before fun clean() { bak.delete() }

    @After fun restore() {
        bak.delete()
        app.store.reload()
    }

    /** A fresh Store over the same folder, read from disk, which is what a cold start does. */
    private fun coldStore(): Store = Store(inst.targetContext).also { runBlocking { it.load() } }

    @Test fun oneUnreadableEntryDoesNotDiscardTheRest() {
        // "shelves" with a null inside used to throw out of AppSettings.from and take every other setting
        // with it, which is why a restored install came up asking to be onboarded again.
        app.store.flush()
        file.writeText(
            """{"swipeThreshold":77,"onboarded":true,"theme":"DARK","shelves":[null,"Favourites"],"readingWpm":300}""",
        )
        assertEquals("the test file was overwritten before it could be read", 77, org.json.JSONObject(file.readText()).getInt("swipeThreshold"))

        val cold = coldStore()

        assertEquals(77, cold.settings.value.swipeThreshold)
        assertTrue("onboarding state was lost", cold.settings.value.onboarded)
        assertEquals(300, cold.settings.value.readingWpm)
        assertEquals(listOf("Favourites"), cold.settings.value.shelves)
        assertFalse("a readable file must not be copied aside", bak.exists())
    }

    @Test fun aFileThatIsNotJsonIsKeptBesideTheDefaults() {
        app.store.flush()
        file.writeText("{ this is not json")

        val cold = coldStore()

        assertTrue("the unreadable settings file was not preserved", bak.exists())
        assertEquals("{ this is not json", bak.readText())
        // Defaults are used for this run, but nothing was destroyed on disk.
        assertEquals(40, cold.settings.value.swipeThreshold)
    }
}
