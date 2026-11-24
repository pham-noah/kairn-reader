package app.kairn

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Library
import app.kairn.data.PositionsCache
import app.kairn.data.ReadiumHelper
import app.kairn.data.Store
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import is supposed to leave a book with its positions already measured, so the first open takes the cached
 * path like every other open. This drives the real `Library.addBook` over a real EPUB and then waits for the
 * background measurement to land - the claim is a cache file that parses back into positions, not "it compiles".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportPositionsTest {

    private fun epub(dir: File): File {
        val f = File(dir, "tiny.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            // The mimetype entry must be first and stored, or the file is not a valid EPUB.
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = "application/epub+zip".toByteArray().size.toLong()
                compressedSize = size
                crc = java.util.zip.CRC32().apply { update("application/epub+zip".toByteArray()) }.value
            })
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent())
            put("OEBPS/content.opf", """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:kairn-import-probe</dc:identifier>
                    <dc:title>Import Probe</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
            """.trimIndent())
            // Two chapters of real prose: positions are counted from the text, so a one-word chapter would prove
            // nothing about the measurement.
            listOf("chapter1.xhtml", "chapter2.xhtml").forEach { name ->
                put("OEBPS/$name", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Probe</title></head><body>
                    <h1>A chapter</h1>
                    ${(1..40).joinToString(" ") { "This is sentence number $it of the probe chapter, with enough words in it to be counted as text and to give the position counter something to measure." }}
                    </body></html>
                """.trimIndent())
            }
        }
        return f
    }

    @Test fun `an imported book leaves with its positions already measured`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("kairn-import").toFile()
        val store = Store.forTesting(root)
        store.load()
        val readium = ReadiumHelper(ctx)
        val library = Library(ctx, store, readium)

        val file = epub(root)
        val book = library.addBook(file.toUri(), null)
        val cache = PositionsCache(ctx)

        // Before: the reader would have had to measure this itself on first open.
        assertNull("a fresh book starts with no cached positions", cache.load(book))

        // The measurement runs behind the import; give it a few seconds rather than asserting on a race.
        var landed = false
        repeat(60) {
            if (cache.load(book) != null) { landed = true; return@repeat }
            Thread.sleep(500)
        }
        assertTrue("the import should have measured positions in the background", landed)
        val positions = cache.load(book)
        assertNotNull(positions)
        assertTrue("two chapters should yield positions, not an empty list", positions!!.size >= 2)
        assertTrue("a position points into the book", positions.first().href.toString().isNotEmpty())
    }
}
