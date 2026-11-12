package app.kairn

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Backup
import app.kairn.data.Book
import app.kairn.data.BookCast
import app.kairn.data.Catalog
import app.kairn.data.Store
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup is the one file that leaves the phone on purpose, so it is the one place a secret must not be.
 * This drives the real `Backup.write` and reads the archive back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSecretsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun a_catalog_password_never_enters_the_archive_and_the_cast_does() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = Store.forTesting(File(tmp.root, "data-root")).also { runBlocking { it.load() } }
        store.put(Book(id = "b1", title = "Guarded", author = "P", uri = "file:///nope.epub"))
        store.saveCast("b1", BookCast(enabled = true, voices = mapOf("Ada" to 2)))
        store.saveSettings {
            it.copy(catalogs = listOf(Catalog("Calibre", "http://192.168.1.5:8080/opds", "noah", "s3cret-pass")))
        }
        store.flush()

        val out = File(tmp.root, "backup.zip")
        val backup = Backup(ctx, store)
        val result = runBlocking { backup.write(Uri.fromFile(out), includeBookFiles = false) }
        assertTrue("backup failed: ${result.exceptionOrNull()}", result.isSuccess)

        val names = mutableListOf<String>()
        var settings = ""
        ZipFile(out).use { zip ->
            for (e in zip.entries()) {
                names += e.name
                if (e.name == "data/settings.json") settings = zip.getInputStream(e).use { String(it.readBytes()) }
            }
        }
        assertFalse("the password reached the archive: $settings", settings.contains("s3cret-pass"))
        assertTrue("the catalog itself should survive", settings.contains("192.168.1.5:8080"))
        assertTrue("a cast is part of the library: $names", names.any { it == "data/cast/b1.json" })
        // The phone keeps its own password; only the copy is stripped.
        assertEquals("s3cret-pass", store.settings.value.catalogs.single().pass)
    }
}
