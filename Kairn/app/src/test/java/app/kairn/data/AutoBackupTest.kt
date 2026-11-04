package app.kairn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DS-01: the automatic backup's whole path - create, write, prune, record - against a fake folder.
 *
 * It used to be compiled and reviewed and never exercised, because a SAF tree can only be granted from a
 * device's picker; on 2026-11-04 it was proven by hand on an emulator, and this pins the same behaviour down in
 * a JVM test so it stays proven.
 */
class AutoBackupTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeFolder(private val writable: Boolean = true) : BackupFolder {
        val docs = mutableListOf<FolderDoc>()
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun canWrite() = writable

        override fun create(name: String, mime: String): FolderDoc? {
            if (!writable) return null
            val doc = FolderDoc(name, DocHandle("content://test/$name"))
            created += name
            docs += doc
            return doc
        }

        override fun list(): List<FolderDoc> = docs.toList()

        override fun delete(handle: DocHandle) {
            deleted += handle.value.substringAfterLast('/')
            docs.removeAll { it.handle == handle }
        }

        fun seed(vararg names: String) {
            names.forEach { docs += FolderDoc(it, DocHandle("content://test/$it")) }
        }
    }

    private fun store() = Store.forTesting(tmp.root).also { runBlocking { it.load() } }

    private fun Store.setUpFolder(keep: Int = 3, days: Int = 7, last: Long = 0L) = saveSettings {
        it.copy(autoBackupFolder = "content://test/tree", autoBackupKeep = keep, autoBackupDays = days, lastAutoBackup = last)
    }

    @Test fun a_due_backup_writes_one_zip_and_prunes_to_keep() = runBlocking {
        val store = store()
        store.setUpFolder(keep = 3)
        val folder = FakeFolder().apply {
            seed(
                "kairn-auto-20260101-0000.zip", "kairn-auto-20260102-0000.zip",
                "kairn-auto-20260103-0000.zip", "kairn-auto-20260104-0000.zip", "note.txt",
            )
        }
        val writtenTo = mutableListOf<DocHandle>()
        val auto = AutoBackup(store, { folder }, { doc -> writtenTo += doc; Result.success(9) })

        assertTrue("a due backup must write", auto.runIfDue())

        assertEquals(1, folder.created.size)
        assertTrue("the name carries the prefix", folder.created.single().startsWith(AutoBackup.PREFIX))
        assertTrue("the zip was written through the writer", writtenTo.single().value.endsWith(folder.created.single()))
        // Five of ours after the write, keep three: the two oldest go, and a file without the prefix never does.
        assertEquals(listOf("kairn-auto-20260101-0000.zip", "kairn-auto-20260102-0000.zip"), folder.deleted)
        assertTrue("a foreign file is untouched", folder.docs.any { it.name == "note.txt" })
        assertEquals(3, folder.docs.count { it.name?.startsWith(AutoBackup.PREFIX) == true })

        val s = store.settings.value
        assertEquals(folder.created.single(), s.lastBackupName)
        assertTrue("the record moves so the next run is not due", s.lastAutoBackup > 0L)
    }

    @Test fun not_due_writes_nothing() = runBlocking {
        val store = store()
        store.setUpFolder(days = 7, last = System.currentTimeMillis())
        val folder = FakeFolder()
        val auto = AutoBackup(store, { folder }, { Result.success(1) })

        assertFalse(auto.runIfDue())
        assertTrue(folder.created.isEmpty())
    }

    @Test fun no_folder_configured_is_not_a_failure() = runBlocking {
        val store = store()
        val folder = FakeFolder()
        val auto = AutoBackup(store, { folder }, { Result.success(1) })

        assertFalse(auto.runIfDue())
        assertTrue(folder.created.isEmpty())
        assertTrue(store.settings.value.lastBackupName.isEmpty())
    }

    @Test fun a_folder_that_does_not_open_writes_nothing() = runBlocking {
        val store = store()
        store.setUpFolder()
        val auto = AutoBackup(store, { null }, { Result.success(1) })

        assertFalse("a revoked or moved tree must not crash a scheduled run", auto.runIfDue())
        assertEquals(0L, store.settings.value.lastAutoBackup)
    }

    @Test fun a_folder_that_is_not_writable_writes_nothing() = runBlocking {
        val store = store()
        store.setUpFolder()
        val folder = FakeFolder(writable = false)
        val auto = AutoBackup(store, { folder }, { Result.success(1) })

        assertFalse(auto.runIfDue())
        assertTrue(folder.created.isEmpty())
        assertEquals(0L, store.settings.value.lastAutoBackup)
    }

    @Test fun a_failed_write_is_silent_and_is_not_recorded() = runBlocking {
        val store = store()
        store.setUpFolder()
        val folder = FakeFolder()
        val auto = AutoBackup(store, { folder }, { Result.failure(IllegalStateException("disk full")) })

        assertFalse("a scheduled backup never interrupts reading with a dialog", auto.runIfDue())
        assertEquals(1, folder.created.size)                       // the document exists, empty
        assertEquals("a failed write must not look like a backup", 0L, store.settings.value.lastAutoBackup)
        assertTrue(store.settings.value.lastBackupName.isEmpty())
    }
}
