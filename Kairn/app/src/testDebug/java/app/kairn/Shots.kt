package app.kairn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kairn.data.Ambient
import app.kairn.data.Book
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.data.ReadingStats
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.ui.NoteTarget
import app.kairn.ui.ReaderActions
import app.kairn.ui.ReaderScreen
import app.kairn.ui.ReaderUiState
import app.kairn.ui.kit.ToastState
import app.kairn.ui.theme.K
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.data.EmptyContainer
import org.readium.r2.shared.util.logging.WarningLogger
import org.readium.r2.shared.util.logging.Warning
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import app.kairn.data.BookCast
import app.kairn.ui.TtsSheet
import app.kairn.ui.ContentsSheet
import app.kairn.ui.SearchSheet
import app.kairn.ui.screens.BackupScreen
import app.kairn.ui.screens.BackupUi
import app.kairn.ui.screens.CollectionsScreen
import app.kairn.ui.screens.EditBookContent
import app.kairn.ui.screens.CatalogScreen
import app.kairn.ui.screens.AnnotationsContent
import app.kairn.ui.DisplaySheet
import app.kairn.ui.screens.BookDetailContent
import app.kairn.ui.screens.OnboardingScreen
import app.kairn.ui.screens.AddBooksScreen
import app.kairn.ui.screens.LibraryScreen
import app.kairn.ui.screens.SettingsScreen
import app.kairn.ui.theme.Dim
import app.kairn.ui.screens.UpdateContent
import app.kairn.ui.screens.UpdateUi
import app.kairn.ui.screens.StatsContent
import app.kairn.ui.theme.KairnTheme
import app.kairn.ui.theme.readerUi
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshot probe, kept instead of rewritten.
 *
 * Four probes were written from scratch and thrown away in one afternoon; the boilerplate below was retyped
 * every time. It lives here now, skipped in the ordinary suite and the CI gate (so nothing writes PNGs on a
 * build machine) and run only when asked:
 *
 *     KAIRN_SHOTS=1 ./gradlew :app:testDebugUnitTest --tests "app.kairn.Shots"
 *
 * Then read a picture with `vision_analyze` before believing an assertion about how something looks. Sizes are
 * in PIXELS against a fixed density (`xhdpi` = 2), so 822 is a 411 dp phone and 720 a 360 dp one — render the
 * narrow one when the question is crowding.
 *
 * When `ReaderActions` gains a method this stub stops compiling. That is the point: a stale fake keeps
 * working from an old class file and reports green, so nothing here is allowed to be silently absent. If a run
 * ever says a shot is missing, force the compile:
 *
 *     ./gradlew :app:compileDebugUnitTestKotlin --rerun-tasks
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Shots {

    @Before fun only_on_request() {
        assumeTrue("set KAIRN_SHOTS=1 to draw the screens", System.getenv("KAIRN_SHOTS") != null)
    }

    private fun store(books: Boolean): Store {
        val st = Store.forTesting(Files.createTempDirectory("kairn-shots").toFile())
        runBlocking { st.load() }
        if (books) {
            st.put(Book("b1", "The Peregrine", "J. A. Baker", "file:///peregrine.epub", status = Status.READING, progress = 0.42f))
            st.put(Book("b2", "Anna Karenina", "Leo Tolstoy", "file:///ak.epub", status = Status.FINISHED, progress = 1f))
            st.put(Book("b3", "Solenoid", "Mircea Cărtărescu", "file:///sol.epub", progress = 0.1f))
        }
        return st
    }

    private fun shoot(
        name: String, width: Int = 822, height: Int = 1782, fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        // Large type is a setting a reader with tired eyes turns on, and it had never been drawn: every label
        // in the app is sized in sp, so 1.3 is where chips, captions and sheet bodies start to collide.
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setContent {
            KairnTheme(glass = true, ambient = Ambient.MINT) {
                // The shell paints K.bg behind every screen; a shot that skipped it drew the app's near-white
                // text (K.txt is #EEF1F3) onto the harness's own white canvas. The Library and Settings titles
                // measured 1.36:1 in the resulting files and read as missing, which is a defect in the review
                // copy, not in the app. One fill, and a shot is the screen.
                Box(Modifier.fillMaxSize().background(K.bg)) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val view: View = activity.window.decorView
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File(Shot.dir, "$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("shot ${out.absolutePath} ${out.length()} bytes")
        assertTrue("nothing was drawn to $name", out.length() > 2000)
    }

    // A publication good enough to draw a table of contents: the reader's sheets only need manifest links,
    // and building a real EPUB on disk to look at a list of chapters is not worth the ten seconds.
    // A publication good enough to draw a table of contents: the reader's sheets only read manifest links,
    // and building a real EPUB on disk to look at a list of chapters is not worth the ten seconds.
    // Readium 3.0: Link takes `href`, Manifest takes everything by name, and the builder insists on a
    // container - an empty one is enough for a manifest that will never be opened.
    // A publication good enough to draw a table of contents: the reader's sheets only read manifest links,
    // and building a real EPUB on disk to look at a list of chapters is not worth the ten seconds.
    //
    // Parsed from the manifest JSON rather than built from Readium's constructors: Metadata in 3.0 takes a
    // LocalizedString for its title and two dozen defaulted arguments, and none of that matters to a list of
    // chapter names.
    private val manifestWarnings = mutableListOf<String>()
    private var pubError: String? = null

    private fun shelfPub(): Publication? = runCatching {
        val chapters = listOf("The House" to "ch1.xhtml", "The Valley" to "ch2.xhtml", "The Eyrie" to "ch3.xhtml")
        // Robolectric's org.json is the AOSP one, which has no put(String, Collection) and no
        // JSONArray(Collection) - both throw NoSuchMethodError at runtime, not at compile time.
        fun array(vararg items: JSONObject) = JSONArray().also { a -> items.forEach { a.put(it) } }
        fun links() = chapters.map { (t, h) ->
            JSONObject().put("href", h).put("type", "application/xhtml+xml").put("title", t)
        }.toTypedArray()
        val json = JSONObject()
            .put("metadata", JSONObject().put("identifier", "urn:kairn:shots").put("title", "The Peregrine").put("language", "en"))
            .put("links", array(JSONObject().put("rel", "self").put("href", "http://example.com/book.epub")))
            .put("readingOrder", array(*links()))
            .put("toc", array(*links()))
        manifestWarnings.clear()
        val manifest = Manifest.fromJSON(json, object : WarningLogger {
            override fun log(warning: Warning) { manifestWarnings += warning.toString() }
        })
        Publication.Builder(manifest = manifest!!, container = EmptyContainer()).build()
    }.onFailure { pubError = it.toString() }.getOrNull()

    private fun hit(before: String, highlight: String, after: String) =
        Locator(Url("ch2.xhtml")!!, MediaType.XHTML, null,
            Locator.Locations(progression = 0.31), Locator.Text(before = before, highlight = highlight, after = after))

    @Test fun sheet_contents() = shoot("sheet-contents", 822, 980) {
        val pub = shelfPub()
        // A shot that silently falls back to "this book has no table of contents" proves nothing; say so,
        // with whatever the parser complained about.
        assertTrue("no toc; error=${pubError} parser said: ${manifestWarnings.joinToString()}", (pub?.tableOfContents?.size ?: 0) > 0)
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) { ContentsSheet(pub, "ch2.xhtml", {}) }
    }

    @Test fun sheet_search() = shoot("sheet-search", 822, 1200) {
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) {
            SearchSheet(
                results = listOf(
                    hit("the wind came off the", "salt marsh", "and the bird held"),
                    hit("he had watched the", "peregrine", "for three winters"),
                    hit("nothing moved on the", "estuary", "until the tide"),
                ),
                busy = false, canMore = true, onQuery = {}, onMore = {}, onGo = {},
                chapterTitles = mapOf("ch2.xhtml" to "The Valley"), showChapterFilter = true,
            )
        }
    }

    @Test fun annotations() = shoot("annotations", 822, 1250) {
        val marks = listOf(
            Mark("m1", "highlight", 0xFFFFD54F.toInt(), "The peregrine does not hunt; it waits for the world to move first.", "", "loc", 1_700_000_000_000),
            Mark("m2", "note", 0xFF7EE0C3.toInt(), "Nothing in the valley moved for an hour.", "Baker's patience is the whole book.", "loc", 1_700_000_100_000),
            Mark("m3", "highlight", 0xFFFFD54F.toInt(), "He wrote in the margins, as if the margins were the field.", "", "loc", 1_700_000_200_000),
        )
        Column(Modifier.fillMaxSize().padding(horizontal = Dim.screenPad.dp)) {
            AnnotationsContent("The Peregrine", marks, {}, {}, {}, {})
        }
    }

    @Test fun library_list() = shoot("library-list", 822, 1200) {
        val st = store(true)
        runBlocking { st.saveSettings { it.copy(grid = false) } }
        LibraryScreen(st, null, {}, {}, {}, {}, {})
    }

    // The last screens with no picture: the TTS sheet, backup, collections, the catalog's first page and the
    // update sheet. Each is a place a user lands on purpose, and none had been drawn once.
    // Cast on, so the four audition pads are in the picture: they are the answer to a sheet that used to ask a
    // reader to pick between four numbers.
    @Test fun tts_sheet() = shoot("sheet-tts", 822, 1660) {
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) {
            TtsSheet(ReaderPrefs(), sleepChoice = 15, onRate = {}, onSleep = {}, onStop = {},
                cast = BookCast(enabled = true), speakers = listOf("Alba", "Mateo"), onPreview = {})
        }
    }

    @Test fun backup() = shoot("backup", 822, 1400) {
        BackupScreen(BackupUi(), store(true), {}, {}, {}, null, {}, {})
    }

    @Test fun collections() = shoot("collections", 822, 1200) {
        val st = store(true)
        runBlocking {
            st.put(Book("c1", "The Peregrine", "J. A. Baker", "file:///p.epub", status = Status.READING,
                progress = 0.42f, collections = listOf("Favourites"), tags = listOf("nature")))
        }
        CollectionsScreen(st, {})
    }

    @Test fun catalog() = shoot("catalog", 822, 1400) {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        CatalogScreen(store(true), app.kairn.data.Library(ctx, store(true), app.kairn.data.ReadiumHelper(ctx)),
            app.kairn.data.OpdsClient("0.9.0"), {})
    }

    @Test fun update_sheet() = shoot("sheet-update", 822, 760) {
        val ui = UpdateUi().apply {
            open = true
            release = app.kairn.data.Update.Release(
                version = "0.9.1", url = "https://example.com/kairn.apk", size = 12_400_000,
                notes = "The shelf gives half its screen back. Book detail stops listing what it does not have.",
                page = "https://example.com/release",
            )
        }
        // Not UpdateSheet: a KSheet is a Dialog under Robolectric and its body never lays out, which is why
        // UpdateContent exists as its own composable.
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) { UpdateContent(ui, {}, {}, {}) }
    }

    @Test fun update_latest() = shoot("sheet-update-latest", 822, 620) {
        val ui = UpdateUi().apply { open = true; message = "Kairn 0.9.0 is the latest version." }
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) { UpdateContent(ui, {}, {}, {}) }
    }

    @Test fun library_empty() = shoot("library-empty", 822, 1200) {
        LibraryScreen(store(false), null, {}, {}, {}, {}, {})
    }

    @Test fun library_books() = shoot("library-books", 822, 1400) {
        LibraryScreen(store(true), null, {}, {}, {}, {}, {})
    }

    // Breadth: every shot until now was 411 dp wide. A tablet is 600 dp and three screens lay out differently
    // there - the shelf's grid picks its own columns, settings has room it has never used, and the reader
    // switches to a spread. None of that had been looked at once.
    @Config(qualifiers = "w600dp-h900dp-xhdpi")
    @Test fun library_wide() = shoot("library-wide", 1200, 1600) {
        LibraryScreen(store(true), null, {}, {}, {}, {}, {})
    }

    @Config(qualifiers = "w600dp-h900dp-xhdpi")
    @Test fun settings_wide() = shoot("settings-wide", 1200, 2400) {
        SettingsScreen(store(false), {}, {}, {})
    }

    @Config(qualifiers = "w600dp-h900dp-xhdpi")
    @Test fun reader_wide() = shoot("reader-wide", 1200, 1600) {
        ReaderScreen(ui(), readerUi(ThemeId.LIGHT), isTablet = true, toast = ToastState(), act = NoReader, wpm = 240)
    }

    @Test fun library_narrow() = shoot("library-narrow", 720, 1200) {
        LibraryScreen(store(true), null, {}, {}, {}, {}, {})
    }

    // The first screen a new install shows, and the screen every book arrives through. Neither had ever
    // been drawn: the library was reviewed six times before anyone looked at the way into it.
    @Test fun onboarding() = shoot("onboarding", 822, 1400) {
        OnboardingScreen(store(false), {})
    }

    @Test fun add_books() = shoot("add-books", 822, 1500) {
        AddBooksScreen(store(true), {}, {}, {}, { _, _ -> })
    }

    @Test fun settings() = shoot("settings", 822, 2600) {
        SettingsScreen(store(false), {}, {}, {})
    }

    // The third answer to "switch automatically": a window the reader picks, which the app decides instead of
    // asking the phone where it thinks the sun is.
    // Large type, three tiles: the longest label in the app set is スケジュール in the same width.
    @Test fun settings_schedule_big() = shoot("settings-schedule-big", 822, 3200, fontScale = 1.3f) {
        val st = store(false)
        st.saveSettings { it.copy(autoSwitch = true, autoMode = "SCHEDULE", theme = ThemeId.LIGHT) }
        SettingsScreen(st, {}, {}, {})
    }

    @Test fun settings_schedule() = shoot("settings-schedule", 822, 2700) {
        val st = store(false)
        st.saveSettings { it.copy(autoSwitch = true, autoMode = "SCHEDULE") }
        SettingsScreen(st, {}, {}, {})
    }

    @Test fun reader_dark() = shoot("reader-dark") {
        ReaderScreen(ui(), readerUi(ThemeId.DARK), isTablet = false, toast = ToastState(), act = NoReader, wpm = 240)
    }

    // The three sheet bodies nobody had ever drawn. Every sheet is a Dialog under Robolectric, so the wrapper
    // never lays out - the body is the thing to shoot, and the body is where a wrong label lives.
    // A sheet body carries no padding of its own: KSheet's Column supplies Dim.screenPad, so a body shot raw
    // draws flush left and clips at the right edge, which reads as a layout bug that is not in the app.
    @Test fun display_sheet() = shoot("sheet-display", 822, 3400) {
        Column(Modifier.padding(horizontal = Dim.screenPad.dp).padding(top = 8.dp)) {
            // With both footers drawn: reset this book, and the one that hands this page to every other book.
            DisplaySheet(ReaderPrefs(), {}, onReset = {}, onApplyToAll = {}, maxHeight = 3400.dp)
        }
    }

    @Test fun stats_sheet() = shoot("sheet-stats", 822, 1180) {
        val today = java.time.LocalDate.now()
        val days = (0 until 14).associate { back ->
            today.minusDays(back.toLong()).toString() to (if (back % 4 == 0) 0 else 12 + (back * 7) % 55)
        } + (today.toString() to 24)   // a goal is only worth drawing on a day that has something in it
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) {
            StatsContent(ReadingStats(days = days, finished = setOf("b2")), finishedBooks = 7, goal = 30)
        }
    }

    // The same sheet on a day the goal is behind you: the bar is full and the line says so.
    @Test fun stats_sheet_goal_met() = shoot("sheet-stats-met", 822, 1180) {
        val today = java.time.LocalDate.now()
        val days = (1 until 14).associate { back ->
            today.minusDays(back.toLong()).toString() to (if (back % 4 == 0) 0 else 12 + (back * 7) % 55)
        } + (today.toString() to 47)
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) {
            StatsContent(ReadingStats(days = days, finished = setOf("b2")), finishedBooks = 7, goal = 30)
        }
    }

    @Test fun stats_sheet_empty() = shoot("sheet-stats-empty", 822, 1100) {
        Column(Modifier.padding(horizontal = Dim.screenPad.dp)) { StatsContent(ReadingStats(), finishedBooks = 0) }
    }

    // The sheet that corrects an import: a catalog's OPF is often wrong about the series, and the shelf sorts
    // by it, so a bad one is visible for ever.
    @Test fun edit_book() = shoot("sheet-edit", 822, 1500) {
        val b = Book("b1", "The Peregrine", "J. A. Baker", "file:///peregrine.epub", series = "Nature Writing", seriesIndex = 2.0)
        Column(Modifier.fillMaxSize().padding(horizontal = Dim.screenPad.dp)) {
            EditBookContent(b, store(false)) {}
        }
    }

    @Test fun library_big() = shoot("library-big", 822, 1700, fontScale = 1.3f) {
        LibraryScreen(store(true), null, {}, {}, {}, {}, {})
    }

    @Test fun settings_big() = shoot("settings-big", 822, 2900, fontScale = 1.3f) {
        SettingsScreen(store(false), {}, {}, {})
    }

    @Test fun book_detail() = shoot("sheet-book", 822, 1500) {
      Column(Modifier.padding(horizontal = Dim.screenPad.dp)) {
        BookDetailContent(
            book = Book(
                "b1", "The Peregrine", "J. A. Baker", "file:///peregrine.epub",
                status = Status.READING, progress = 0.42f,
            ),
            store = store(false), size = 1_734_221L, expanded = true, wpm = 240,
            onExpand = {}, onExport = {}, onToggleFinished = {}, onOpen = {}, onEdit = {}, onRemove = {},
        )
      }
    }

    @Test fun reader_light() = shoot("reader-light") {
        ReaderScreen(ui(), readerUi(ThemeId.LIGHT), isTablet = false, toast = ToastState(), act = NoReader, wpm = 240)
    }

    /** Chrome only: with no publication the page area is blank and the bars are what there is to judge. */
    private fun ui() = ReaderUiState().apply {
        loading = false
        bars = true
        bookTitle = "The Peregrine"
        chapterTitle = "Chapter Three"
        prefs = ReaderPrefs()
    }
}

/** The reader's callbacks, all still: a shot does not turn pages. */
private object NoReader : ReaderActions {
    override fun close() {}
    override fun toggleBars() {}
    override fun goTo(locator: Locator) {}
    override fun preload(locator: Locator) {}
    override fun setPrefs(p: ReaderPrefs) {}
    override fun applyPrefsToAll() {}
    override fun previewVoice(slot: Int) {}
    override fun resetPrefs() {}
    override fun toggleBookmark(bookmarked: Boolean) {}
    override fun saveNote(target: NoteTarget, text: String) {}
    override fun updateNote(id: String, text: String) {}
    override fun deleteMark(m: Mark) {}
    override fun deleteBook() {}
    override fun exportNotes() {}
    override fun search(query: String) {}
    override fun toggleTts() {}
    override fun stopTts() {}
    override fun setCastEnabled(on: Boolean) {}
    override fun setCastVoice(speaker: String, slot: Int) {}
    override fun setSpeechRate(rate: Float) {}
    override fun setSleepTimer(minutes: Int) {}
    override fun toggleAutoScroll() {}
    override fun searchMore() {}
}

/** Where the PNGs land: Hermes's scratch dir, so `vision_analyze` can read them straight back. */
internal object Shot {
    val dir: File = File(System.getenv("KAIRN_SHOT_DIR") ?: "C:/Users/Noah/AppData/Local/hermes/cache/scratch")
        .also { it.mkdirs() }
}
