package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The import rule that protects a book already on the phone: a second file with the same display name must
 * get a name of its own, never the copy another record points at.
 */
class LibraryCopyNameTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun taken(vararg names: String) = names.forEach { File(tmp.root, it).writeText("x") }

    @Test fun `a free name is the name itself`() {
        assertEquals("Dune.epub", freeCopyName(tmp.root, "Dune.epub").name)
    }

    @Test fun `a name already taken gets a numbered sibling`() {
        taken("Dune.epub")
        assertEquals("Dune-1.epub", freeCopyName(tmp.root, "Dune.epub").name)
    }

    @Test fun `numbering skips the ones already there and keeps the extension last`() {
        taken("Dune.epub", "Dune-1.epub", "Dune-2.epub")
        assertEquals("Dune-3.epub", freeCopyName(tmp.root, "Dune.epub").name)
    }

    @Test fun `a name with no extension still gets a sibling`() {
        taken("book")
        assertEquals("book-1", freeCopyName(tmp.root, "book").name)
    }

    @Test fun `the first copy is never the file it had to avoid`() {
        taken("Dune.epub")
        val first = File(tmp.root, "Dune.epub")
        val dest = freeCopyName(tmp.root, "Dune.epub")
        assertTrue("a fresh name must not exist yet", !dest.exists())
        assertTrue("and it must not be the existing copy", dest.absolutePath != first.absolutePath)
    }
}
