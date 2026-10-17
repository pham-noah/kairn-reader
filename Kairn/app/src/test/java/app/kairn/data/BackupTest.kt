package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The restore path can destroy a library, and it had no test at all. These cover the two things that
 * protect it: rejecting a file that is not a Kairn backup BEFORE anything is deleted, and merging a
 * book without losing a mark.
 */
class BackupTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            names.forEach { z.putNextEntry(ZipEntry(it)); z.write("x".toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun book(id: String, title: String, lastRead: Long, vararg markIds: String) = Book(
        id = id, title = title, author = "", uri = "u", lastRead = lastRead,
        marks = markIds.map { Mark(it, "highlight", 0, "q-$it", "", "{}", 1L) },
    )

    private fun backupish(vararg names: String) = isKairnBackup(zipEntryNames(ByteArrayInputStream(zipOf(*names))))

    @Test fun `a Kairn backup is recognised by its data entries`() {
        assertTrue(backupish("data/settings.json", "data/books/abc.json"))
    }

    @Test fun `an empty-library backup is still accepted`() {
        // Only settings.json: a backup of a library with no books is legitimate and must not be rejected.
        assertTrue(backupish("data/settings.json"))
    }

    @Test fun `a file that is not a zip is rejected, so nothing gets deleted for it`() {
        assertTrue(!isKairnBackup(zipEntryNames(ByteArrayInputStream("this is not a zip at all".toByteArray()))))
    }

    @Test fun `a zip with no data entries is rejected`() {
        assertTrue(!backupish("holiday/photos.jpg"))
    }

    @Test fun `merging unions the marks and takes the newer record`() {
        val target = File(tmp.root, "abc.json")
        target.writeText(book("abc", "Mine", 100L, "m1").toJson().toString())
        val incoming = book("abc", "Theirs", 200L, "m2").toJson().toString().toByteArray()
        mergeBook(target, incoming, replace = false)
        val merged = Book.from(org.json.JSONObject(target.readText()))!!
        assertEquals(2, merged.marks.size)                       // nothing lost from either side
        assertEquals(setOf("m1", "m2"), merged.marks.map { it.id }.toSet())
        assertEquals("Theirs", merged.title)                     // newer lastRead wins
    }

    @Test fun `an existing mark is not duplicated by a re-import`() {
        val target = File(tmp.root, "abc.json")
        target.writeText(book("abc", "Mine", 100L, "m1").toJson().toString())
        mergeBook(target, book("abc", "Mine", 100L, "m1").toJson().toString().toByteArray(), replace = false)
        assertEquals(1, Book.from(org.json.JSONObject(target.readText()))!!.marks.size)
    }

    @Test fun `an unreadable incoming record never overwrites ours`() {
        val target = File(tmp.root, "abc.json")
        val mine = book("abc", "Mine", 100L, "m1").toJson().toString()
        target.writeText(mine)
        mergeBook(target, "not json".toByteArray(), replace = false)
        assertEquals(mine, target.readText())
    }

    @Test fun `replace mode overwrites unconditionally`() {
        val target = File(tmp.root, "abc.json")
        target.writeText(book("abc", "Mine", 100L, "m1").toJson().toString())
        mergeBook(target, book("abc", "Theirs", 1L, "m9").toJson().toString().toByteArray(), replace = true)
        val after = Book.from(org.json.JSONObject(target.readText()))!!
        assertEquals("Theirs", after.title)
        assertEquals(listOf("m9"), after.marks.map { it.id })
    }
}
