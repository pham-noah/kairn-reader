package app.kairn.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data layer is where "your data is never lost" is won or lost. These tests run on the JVM
 * (no device): every model is written to JSON on disk and read back on every launch, so a
 * round-trip that drops a field is data loss the user would never see coming.
 */
class ModelsTest {

    private fun roundTrip(b: Book): Book =
        Book.from(JSONObject(b.toJson().toString())) ?: error("a full record must survive a round-trip: ${b.id}")

    @Test fun `a plain book survives a JSON round-trip`() {
        val before = Book(id = "abc", title = "Alice", author = "Carroll", uri = "content://book/1")
        val after = roundTrip(before)
        assertEquals("abc", after.id)
        assertEquals("Alice", after.title)
        assertEquals("Carroll", after.author)
        assertEquals("content://book/1", after.uri)
        assertNull(after.folder)
        assertNull(after.locator)
        assertEquals(Status.NONE, after.status)
        assertTrue(after.marks.isEmpty())
    }

    @Test fun `reading position, status and prefs survive a round-trip`() {
        val before = Book(
            id = "abc", title = "T", author = "A", uri = "u",
            status = Status.READING, progress = 0.42f, lastRead = 1234L,
            locator = """{"href":"c1.html"}""",
            prefs = ReaderPrefs(theme = ThemeId.SEPIA, fontScale = 1.4, scroll = true, twoPage = true),
        )
        val after = roundTrip(before)
        assertEquals(Status.READING, after.status)
        assertEquals(0.42f, after.progress, 0.0001f)
        assertEquals(1234L, after.lastRead)
        assertEquals("""{"href":"c1.html"}""", after.locator)
        assertEquals(ThemeId.SEPIA, after.prefs?.theme)
        assertEquals(1.4, after.prefs?.fontScale ?: 0.0, 0.0001)
        assertTrue(after.prefs?.scroll == true)
        assertTrue(after.prefs?.twoPage == true)
    }

    @Test fun `notes, highlights and tags are never dropped`() {
        val mark = Mark("m1", "highlight", 0xFFE8B45C.toInt(), "once upon", "my note", """{"href":"c1"}""", 99L)
        val before = Book(id = "abc", title = "T", author = "", uri = "u", marks = listOf(mark))
            .copy(tags = listOf("fantasy", "owned"), collections = listOf("Shelf"))
        val after = roundTrip(before)
        assertEquals(1, after.marks.size)
        assertEquals("my note", after.marks[0].note)
        assertEquals("once upon", after.marks[0].quote)
        assertEquals(0xFFE8B45C.toInt(), after.marks[0].color)
        assertEquals(listOf("fantasy", "owned"), after.tags)
        assertEquals(listOf("Shelf"), after.collections)
    }

    @Test fun `a moved file keeps its id, so position and notes stay attached`() {
        // The whole point of content-hashed ids: the model stores the id, not the location.
        val first = Book(id = "hash", title = "T", author = "", uri = "content://old/location")
        val second = first.copy(uri = "content://new/location")
        assertEquals(first.id, roundTrip(second).id)
        assertEquals("content://new/location", roundTrip(second).uri)
    }

    @Test fun `settings survive a round-trip including folders and defaults`() {
        val before = AppSettings(
            theme = ThemeId.BLACK, autoSwitch = true, autoDetect = false, onboarded = true,
            folders = listOf(WatchedFolder("content://tree/1", "Books", enabled = false)),
            shelves = listOf("Shelf A"), extraTags = listOf("tag"),
            lastBackup = 555L, lastBackupName = "kairn.zip",
            defaults = ReaderPrefs(theme = ThemeId.LIGHT, justify = true),
        )
        val after = AppSettings.from(JSONObject(before.toJson().toString()))
        assertEquals(ThemeId.BLACK, after.theme)
        assertTrue(after.autoSwitch)
        assertTrue(!after.autoDetect)
        assertTrue(after.onboarded)
        assertEquals(1, after.folders.size)
        assertEquals("Books", after.folders[0].name)
        assertTrue(!after.folders[0].enabled)
        assertEquals(listOf("Shelf A"), after.shelves)
        assertEquals(555L, after.lastBackup)
        assertEquals("kairn.zip", after.lastBackupName)
        assertEquals(ThemeId.LIGHT, after.defaults.theme)
        assertTrue(after.defaults.justify)
    }

    @Test fun `an older settings file without the new keys still loads`() {
        // Forward compatibility: a file written before autoDetect existed must not crash or lose data.
        val old = JSONObject("""{"theme":"SEPIA","keepScreenOn":false,"shelves":["X"]}""")
        val s = AppSettings.from(old)
        assertEquals(ThemeId.SEPIA, s.theme)
        assertTrue(!s.keepScreenOn)
        assertEquals(listOf("X"), s.shelves)
        assertTrue("a new key must fall back to its default", s.autoDetect)
    }

    @Test fun `annotations export as markdown with quotes and notes`() {
        val b = Book(
            id = "1", title = "Alice", author = "Carroll", uri = "u",
            marks = listOf(
                Mark("m1", "highlight", 0, "Down the rabbit-hole", "", "{}", 1L),
                Mark("m2", "bookmark", 0, "Chapter 2", "", "{}", 2L),
            ),
        )
        val md = b.annotationsMarkdown()
        assertTrue(md.contains("# Alice"))
        assertTrue(md.contains("*Carroll*"))
        assertTrue(md.contains("> Down the rabbit-hole"))
        assertTrue(md.contains("Bookmark"))
    }

    @Test fun `a record with no id or uri is rejected instead of silently vanishing`() {
        // reload() is mapNotNull { Book.from(...) } and Book.from used to THROW on a missing id, so one
        // bad field dropped the book out of the library with no message while its file stayed on disk.
        assertNull(Book.from(JSONObject("""{"title":"T","uri":"u"}""")))
        assertNull(Book.from(JSONObject("""{"id":"x","title":"T"}""")))
        assertNull(Mark.from(JSONObject("""{"kind":"highlight","quote":"no id, no locator"}""")))
    }

    @Test fun `one malformed mark does not take the book with it`() {
        val o = JSONObject(
            """{"id":"x","title":"T","uri":"u","marks":[
                 {"kind":"highlight","quote":"lost"},
                 {"id":"m2","kind":"highlight","quote":"kept","locator":"{}"}
               ]}"""
        )
        val b = Book.from(o)!!
        assertEquals(1, b.marks.size)
        assertEquals("kept", b.marks[0].quote)
    }

    @Test fun `a corrupt locator string does not crash the book load`() {
        val o = JSONObject("""{"id":"x","title":"T","uri":"u","locator":"not json at all"}""")
        val b = Book.from(o)!!
        assertEquals("not json at all", b.locator)   // kept verbatim; never thrown away
    }
}
