package app.kairn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The direct-storage walk, which until now had no test at all: what counts as a book, what is skipped, and
 * where the depth cap bites. Everything here is [File], so it runs on the JVM with no device.
 */
class EpubSweeperTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun walk(root: File): List<String> = runBlocking {
        val out = mutableListOf<String>()
        findEpubs(root) { out += it.name }
        out
    }

    @Test fun `an epub is found whatever its case, and other files are not`() {
        tmp.newFile("a.epub")
        tmp.newFile("b.EPUB")
        tmp.newFile("cover.jpg")
        tmp.newFile("notes.txt")
        assertEquals(listOf("a.epub", "b.EPUB").sorted(), walk(tmp.root).sorted())
    }

    @Test fun `subdirectories are walked`() {
        val books = tmp.newFolder("books", "sf")
        File(books, "dune.epub").writeText("x")
        assertEquals(listOf("dune.epub"), walk(tmp.root))
    }

    @Test fun `the Android directory is skipped`() {
        val android = tmp.newFolder("Android", "data")
        File(android, "private.epub").writeText("x")
        assertTrue(walk(tmp.root).isEmpty())
    }

    @Test fun `the walk stops at the depth cap`() {
        var deep = tmp.root
        for (i in 0 until MAX_DEPTH + 2) deep = File(deep, "d$i").also { it.mkdirs() }
        File(deep, "buried.epub").writeText("x")
        assertTrue(walk(tmp.root).isEmpty())

        var shallow = tmp.root
        for (i in 0 until MAX_DEPTH - 1) shallow = File(shallow, "s$i").also { it.mkdirs() }
        File(shallow, "shallow.epub").writeText("x")
        assertEquals(listOf("shallow.epub"), walk(tmp.root))
    }

    @Test fun `one file is reported once across roots`() {
        tmp.newFile("one.epub")
        val seen = mutableSetOf<String>()
        val hits = mutableListOf<String>()
        val report: suspend (File) -> Unit = { hits += it.name }
        runBlocking {
            findEpubs(File(tmp.root, "one.epub"), 0, seen, report)
            findEpubs(File(tmp.root, "one.epub"), 0, seen, report)
        }
        assertEquals(listOf("one.epub"), hits)
    }
}
