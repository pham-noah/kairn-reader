@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.kairn.ui

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.kairn.KairnApp
import app.kairn.R
import app.kairn.data.*
import app.kairn.data.ThemeId
import app.kairn.ui.kit.ToastState
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KairnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import java.util.UUID
import org.readium.r2.navigator.preferences.Color as RColor
import org.readium.r2.navigator.preferences.TextAlign as RTextAlign

/**
 * The reader's Activity: the navigator, the gestures and the writes.
 * This class keeps only what needs Android: the fragment container, Readium wiring, input events and the debounced position save.
 */
class ReaderActivity : AppCompatActivity(), ReaderActions {
    companion object { const val EXTRA_ID = "book_id" }

    private val app get() = application as KairnApp
    private lateinit var bookId: String
    private var pub: Publication? = null
    private var nav: EpubNavigatorFragment? = null
    private lateinit var container: FragmentContainerView

    /** All screen state; Compose observes these fields, the Activity only mutates them. */
    private val ui = ReaderUiState()
    private val toast = ToastState()

    private var rtl = false
    private var canScrollChapter = false
    private var loggedScroll = false
    private var positionsPerHref: Map<String, Int> = emptyMap()   // how many positions each chapter holds
    private val measuredChapters = HashSet<String>()              // each chapter feeds the pace once
    private var searchJob: Job? = null
    private val wordSplit = Regex("\\s+")
    // Every write to this book's record goes through one place; see ReaderWriter.
    private val writer by lazy { ReaderWriter(app.store, bookId) }

    private val exportNotesLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { u ->
        val b = app.store.get(bookId)
        // This callback runs on the main thread: annotationsMarkdown() and the write are disk IO, so both run on IO.
        if (u != null && b != null) lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(u)?.use { it.write(b.annotationsMarkdown().toByteArray()) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pass null: a restored fragment cannot be rebuilt after process death (no factory yet); the
        // reader reopens from the saved position (R-09) instead.
        super.onCreate(null)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bookId = intent.getStringExtra(EXTRA_ID) ?: return finish()

        container = FragmentContainerView(this).apply { id = View.generateViewId() }
        val root = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            setContent {
                // collectAsState, not .value: a StateFlow read inside composition is not observed, so the
                // glass and ambient settings would not update while the reader is open.
                val s by app.store.settings.collectAsState()
                KairnTheme(glass = s.glass, ambient = s.ambient) {
                    ReaderScreen(
                        ui = ui, theme = effectiveTheme(ui.prefs), isTablet = isTablet,
                        toast = toast, act = this@ReaderActivity,
                    )
                }
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        val book = app.store.get(bookId)
        if (book != null) {
            applyBook(book)
            lifecycleScope.launch { openBook(book) }
        } else lifecycleScope.launch {
            // The record may be missing, or the first library read may not have finished yet - a cold
            // "Open with" can arrive before it does. Wait for the read, then retry rather than closing.
            app.store.awaitLoaded()
            val late = app.store.get(bookId) ?: return@launch finish()
            applyBook(late)
            openBook(late)
        }
    }

    /** The parts of the reader that come straight from the stored record. */
    private fun applyBook(book: Book) {
        ui.bookTitle = book.title
        ui.prefs = book.prefs ?: app.store.settings.value.defaults
        ui.marks = book.marks
        if (app.store.settings.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private suspend fun openBook(book: Book) {
        val t0 = SystemClock.elapsedRealtime()
        // Parsing the EPUB is blocking and this coroutine runs on the main dispatcher: Readium's
        // AssetRetriever and PublicationOpener reference no dispatcher, so this must hop to IO (as
        val p = withContext(Dispatchers.IO) { app.readium.open(android.net.Uri.parse(book.uri)) }
        if (p == null) {                                    // N-04: message, never a crash
            writer.markBroken(); ui.error = "bad"; ui.loading = false; return
        }
        pub = p
        ui.pub = p                    // state: Contents and the seek fallback read it during composition
        rtl = p.metadata.readingProgression == ReadingProgression.RTL
        // Load the whole-book position list for the seek slider. positions() scans the whole
        // publication and is the slow part of opening: it belongs on IO, never the main thread.
        val tp = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val computed = withContext(Dispatchers.IO) { runCatching { p.positions() }.getOrDefault(emptyList()) }
            ui.positions = computed
            positionsPerHref = computed.groupingBy { it.href.toString() }.eachCount()   // the whole-book time estimate needs this share
            android.util.Log.i("KairnPerf", "positions n=${ui.positions.size} ${SystemClock.elapsedRealtime() - tp}ms")
        }
        // href -> chapter title, from the table of contents, so scrubbing can name the chapter.
        run {
            val map = HashMap<String, String>()
            fun walk(ls: List<Link>) {
                ls.forEach { l ->
                    l.title?.takeIf { it.isNotBlank() }?.let { t -> map[l.href.toString()] = t; map[l.href.toString().substringAfterLast('/')] = t }
                    walk(l.children)
                }
            }
            walk(p.tableOfContents)
            p.readingOrder.forEach { l -> l.title?.takeIf { it.isNotBlank() }?.let { t -> map.putIfAbsent(l.href.toString(), t) } }
            ui.chapterTitles = map
        }

        val factory = EpubNavigatorFactory(p)
        val highlightCallback = HighlightActionModeCallback(
            K.highlightColors.map { (n, c) -> n to c.toArgb() },
            onCopy = { copySelection() },
            // All named: a trailing lambda now binds to onNote, which is the last parameter.
            onHighlight = { c, done -> highlightSelection(c) { done() } },
            onNote = { done -> startNote(done) },
        )
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() },
            initialPreferences = toEpub(ui.prefs),
            listener = object : EpubNavigatorFragment.Listener {
                override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
                    if (context is HyperlinkNavigator.FootnoteContext) {   // R-08: footnote pop-up
                        ui.footnote = HtmlCompat.fromHtml(context.noteContent, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                        return false
                    }
                    ui.jumpBack = nav?.currentLocator?.value                  // R-08: remember where we were
                    return true
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {}   // external links are ignored
            },
            // A-02: the system selection menu (long-press) gets Kairn's highlight colours.
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = highlightCallback),
        )
        supportFragmentManager.commitNow { replace(container.id, EpubNavigatorFragment::class.java, Bundle(), "reader") }
        val n = supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
        nav = n
        // Keep the loading cover until the first locator arrives: the navigator opens at the saved
        // position, and clearing it now would flash the top of the chapter before it settles.

        n.addInputListener(object : InputListener {            // R-07: tap zones
            override fun onTap(event: TapEvent): Boolean {
                // Scroll mode has no pages: a tap only shows/hides the bars, unless the chapter is
                // too short to scroll, in which case the edge zones stay live so it is not a dead end.
                if (ui.prefs.scroll) {
                    if (canScrollChapter) { toggleBars(); return true }
                    val w0 = container.width.toFloat(); val x0 = event.point.x
                    when {
                        x0 < w0 * 0.3f -> if (rtl) n.goForward(true) else n.goBackward(true)
                        x0 > w0 * 0.7f -> if (rtl) n.goBackward(true) else n.goForward(true)
                        else -> toggleBars()
                    }
                    return true
                }
                val w = container.width.toFloat(); val x = event.point.x
                when {
                    x < w * 0.3f -> if (rtl) n.goForward(true) else n.goBackward(true)
                    x > w * 0.7f -> if (rtl) n.goBackward(true) else n.goForward(true)
                    else -> toggleBars()
                }
                return true
            }
        })

        // Readium's R2BasicWebView overrides onScrollChanged, so setOnScrollChangeListener never
        startScrollWatcher(n)

        // Timing: how long the whole open took (parse + navigator + first position).
        android.util.Log.i("KairnPerf", "openBook ${SystemClock.elapsedRealtime() - t0}ms title='${book.title.take(32)}'")

        // R-09: save the position, debounced. The UI state follows every locator immediately; only
        lifecycleScope.launch {
            var lastSaved = 0L
            n.currentLocator.collectLatest { loc ->
                if (ui.loading) ui.loading = false        // first real position: reveal the page
                // WHOLE-BOOK progression, not the chapter's: the slider and the time left are about
                // the book (progression is the position inside the current resource, not the book).
                loc.locations.totalProgression?.let { ui.bookProgress = it.toFloat() }
                ui.chapterTitle = loc.title ?: ""
                if (loc.href.toString() != ui.chapterHref) { ui.chapterHref = loc.href.toString(); loadChapterWords(loc) }
                if (ui.jumpBack?.href == loc.href && ui.jumpBack?.locations?.progression == loc.locations.progression) ui.jumpBack = null
                delay(if (SystemClock.elapsedRealtime() - lastSaved >= 5_000) 0 else 800)
                lastSaved = SystemClock.elapsedRealtime()
                savePosition(loc)
            }
        }
        // A-01: draw highlights when the marks change, not on every emission of the library flow,
        // which also fires for each position save and would rebuild all decorations every time.
        lifecycleScope.launch {
            app.store.books.map { list -> list.firstOrNull { it.id == bookId }?.marks }.distinctUntilChanged().collect { m ->
                m ?: return@collect
                ui.marks = m
                n.applyDecorations(m.filter { it.kind != "bookmark" }.mapNotNull { mk ->
                    runCatching { Decoration(mk.id, Locator.fromJSON(JSONObject(mk.locator))!!, Decoration.Style.Highlight(tint = mk.color)) }.getOrNull()
                }, "marks")
            }
        }
        applyPrefs()
        applyWindow()
    }

    /** Cheap on the caller's thread: the writer hands the record to the store, which queues the disk write. */
    private fun savePosition(loc: Locator) = writer.savePosition(loc)

    override fun onPause() {
        super.onPause()
        // The prefs write is debounced: a close inside that window would drop the last change.
        writer.savePrefs(ui.prefs)
        nav?.currentLocator?.value?.let(::savePosition)
    }
    override fun onDestroy() { pub?.close(); super.onDestroy() }

    // ---- preferences (R-03, R-04, R-05, R-06, R-10) ----

    /** Tablet = the OS threshold (600dp shortest side). Two-page columns are a wide-screen idea. */
    private val isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

    private fun effectiveTheme(p: ReaderPrefs): ThemeId {
        if (!app.store.settings.value.autoSwitch) return p.theme
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // Exhaustive on purpose: an `else` arm would silently fold a future ThemeId into
        // LIGHT/DARK instead of failing to compile.
        return when (p.theme) {
            ThemeId.BLACK -> if (night) ThemeId.BLACK else ThemeId.LIGHT
            ThemeId.SEPIA -> if (night) ThemeId.DARK else ThemeId.SEPIA
            ThemeId.DARK, ThemeId.LIGHT -> if (night) ThemeId.DARK else ThemeId.LIGHT
            ThemeId.GRAY -> ThemeId.GRAY   // a neutral mid tone reads the same day or night: auto-switch leaves it alone
        }
    }

    private fun toEpub(p: ReaderPrefs): EpubPreferences {
        val t = effectiveTheme(p)
        // BLACK and GRAY are not Readium themes: they are explicit page/ink colours laid over a
        // base theme (GRAY sits on the LIGHT base, because its ink is dark).
        val bg = when (t) { ThemeId.BLACK -> RColor(0xFF000000.toInt()); ThemeId.GRAY -> RColor(0xFF9AA1A6.toInt()); else -> null }
        val ink = when (t) { ThemeId.BLACK -> RColor(0xFFDADDE0.toInt()); ThemeId.GRAY -> RColor(0xFF16191C.toInt()); else -> null }
        return EpubPreferences(
            theme = when (t) {   // exhaustive: BLACK/GRAY carry their colours explicitly below
                ThemeId.LIGHT, ThemeId.GRAY -> Theme.LIGHT
                ThemeId.SEPIA -> Theme.SEPIA
                ThemeId.DARK, ThemeId.BLACK -> Theme.DARK
            },
            backgroundColor = bg,
            textColor = ink,
            fontSize = p.fontScale, lineHeight = p.lineHeight, paragraphSpacing = p.paragraphSpacing,
            pageMargins = p.margins, publisherStyles = false, scroll = p.scroll,
            columnCount = if (p.twoPage && isTablet) ColumnCount.TWO else null,      // R-14: reflowable two-page, tablet only. `spread` is fixed-layout only; setting it here crashes.
            textAlign = if (p.justify) RTextAlign.JUSTIFY else RTextAlign.START, hyphens = p.hyphens,
        )
    }

    private var applyJob: Job? = null

    /**
     * A slider emits on every drag tick, so submitting preferences (a Readium pref apply) and writing the
     * book JSON at that rate makes the Display sheet heavy. The UI state follows immediately; the engine
     * and the disk get the settled value ~120 ms later. onPause flushes it, so a quick close cannot lose it.
     */
    private fun updatePrefs(p: ReaderPrefs) {
        ui.prefs = p
        applyJob?.cancel()
        applyJob = lifecycleScope.launch {
            delay(120)
            writer.savePrefs(p)     // R-10: per book
            applyPrefs()
        }
    }

    private fun applyPrefs() {
        nav?.submitPreferences(toEpub(ui.prefs))
        window.attributes = window.attributes.also { it.screenBrightness = if (ui.prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else ui.prefs.brightness }
    }

    // ---- window (R-11) ----

    override fun toggleBars() { ui.bars = !ui.bars; applyWindow() }
    private fun applyWindow() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (app.store.settings.value.fullscreen && !ui.bars) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // R-07: volume-key page turns. Not while a sheet is open: the search field and the note editor are in
    // this window, and a page turn must not fire while the reader is typing.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (app.store.settings.value.volumeKeys && nav != null && ui.sheet == ReaderSheet.NONE) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { nav!!.goBackward(true); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { nav!!.goForward(true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (app.store.settings.value.volumeKeys && ui.sheet == ReaderSheet.NONE && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) true
        else super.onKeyUp(keyCode, event)

    // Scroll-mode horizontal gestures. The pager's own threshold is too eager: a small sideways
    // ourselves and swallow anything shorter, so the pager never sees it either.
    private var downX = 0f; private var downY = 0f; private var armed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!ui.prefs.scroll) return super.dispatchTouchEvent(ev)

        // The threshold comes from Settings > Reading > "Swipe distance".
        val flick = app.store.settings.value.swipeThreshold * resources.displayMetrics.density
        val swipeOn = app.store.settings.value.swipeChapters
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; armed = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (!armed && kotlin.math.abs(dx) > flick && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 3f) armed = true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                val wasArmed = armed; armed = false
                if (wasArmed || (kotlin.math.abs(dx) > flick && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 3f)) {
                    if (swipeOn) {
                        // Left drag shows the next chapter; right drag the previous one.
                        if (dx < 0) nav?.goForward(true) else nav?.goBackward(true)
                    }
                    return true                                  // consumed: the pager never sees it
                }
            }
            MotionEvent.ACTION_CANCEL -> { armed = false }
        }
        // Past the decision point: keep swallowing, so a wobbling scroll never pages.
        return if (armed) true else super.dispatchTouchEvent(ev)
    }

    /** Copies the current selection (the menu's Copy item, since Readium lets us own the menu). */
    private fun copySelection() = lifecycleScope.launch {
        val n = nav ?: return@launch
        val text = runCatching { n.currentSelection() }.getOrNull()?.locator?.text?.highlight ?: return@launch
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), text))
    }

    /** A-02: add a highlight for the current selection. [done] runs once the mark is saved. */
    private fun highlightSelection(color: Int, done: () -> Unit = {}) = lifecycleScope.launch {
        val n = nav ?: return@launch
        val sel = runCatching { n.currentSelection() }.getOrNull() ?: return@launch
        addMarkTo(Mark(UUID.randomUUID().toString(), "highlight", color, sel.locator.text.highlight ?: "", "", sel.locator.toJSON().toString(), System.currentTimeMillis()))
        n.clearSelection()
        done()
    }

    private fun addMark(kind: String, color: Int) = lifecycleScope.launch {
        val n = nav ?: return@launch
        if (kind == "bookmark") {
            val loc = n.currentLocator.value
            addMarkTo(Mark(UUID.randomUUID().toString(), kind, color, loc.title ?: "", "", loc.toJSON().toString(), System.currentTimeMillis()))
        } else {
            val sel = runCatching { n.currentSelection() }.getOrNull() ?: return@launch   // the other two sites guard this experimental API
            addMarkTo(Mark(UUID.randomUUID().toString(), kind, color, sel.locator.text.highlight ?: "", "", sel.locator.toJSON().toString(), System.currentTimeMillis()))
            n.clearSelection()
        }
    }
    private fun addMarkTo(m: Mark) = writer.addMark(m)

    /**
     * A-02: "Note" on a selection. The selection has to be read while the ActionMode is still open
     * (finishing it clears the WebView selection), so [done] closes the menu after the quote is captured.
     */
    private fun startNote(done: () -> Unit) = lifecycleScope.launch {
        val n = nav
        val sel = n?.let { runCatching { it.currentSelection() }.getOrNull() }
        if (sel != null) {
            ui.noteDraft = NoteTarget(sel.locator.text.highlight.orEmpty(), sel.locator.toJSON().toString())
            n.clearSelection()
        }
        done()      // the menu must close even when the selection could not be read
    }

    // ---- ReaderActions: the writes and the navigator, reached from the screen ----

    /** Notes carry no colour of their own; mint is the colour the Notes list draws for them. */
    override fun saveNote(target: NoteTarget, text: String) {
        addMarkTo(Mark(UUID.randomUUID().toString(), "note", K.mint.toArgb(), target.quote, text, target.locator, System.currentTimeMillis()))
        ui.noteDraft = null
        toast.show(getString(R.string.toast_note_saved))
    }

    /** Deleting an annotation is one tap, so it comes with a five-second Undo that puts it back. */
    override fun deleteMark(m: Mark) {
        writer.deleteMark(m)
        val what = getString(when (m.kind) { "note" -> R.string.kind_note; "bookmark" -> R.string.kind_bookmark; else -> R.string.kind_highlight })
        toast.show(getString(R.string.toast_deleted, what), getString(R.string.toast_undo)) { writer.restoreMark(m) }
    }

    override fun updateNote(id: String, text: String) = writer.updateNote(id, text)

    override fun deleteBook() = writer.removeBook()

    /**
     * One bookmark per chapter: the top-bar button bookmarks the chapter you are in, and pressing it
     * again removes every bookmark in that chapter.
     */
    override fun toggleBookmark(bookmarked: Boolean) {
        val h = ui.chapterHref ?: return
        if (!bookmarked) {
            addMark("bookmark", K.mint.toArgb())
            toast.show(getString(R.string.toast_bookmark_added))
        } else {
            writer.removeBookmarksIn(h, ::markHref)
            toast.show(getString(R.string.toast_bookmark_removed))
        }
    }

    override fun goTo(l: Locator) { nav?.go(l, true); ui.sheet = ReaderSheet.NONE }

    /** R-07: non-animated jump, used to preload the chapter under a dragging slider. */
    override fun preload(l: Locator) { nav?.go(l, animated = false) }

    override fun setPrefs(p: ReaderPrefs) = updatePrefs(p)

    override fun resetPrefs() = updatePrefs(app.store.settings.value.defaults)

    override fun exportNotes() { exportNotesLauncher.launch("${ui.bookTitle.take(40)}-notes.md") }

    override fun close() = finish()

    /**
     * R-03: whether the current chapter is longer than the screen, which decides what a tap in scroll mode
     * does. That only changes when the chapter, the scroll mode or the layout settings change, so this is
     * evaluated once per change rather than polled four times a second - the JS call is not free, and
     * polling it while nothing moves was the battery cost of leaving the reader open.
     */
    private fun startScrollWatcher(n: EpubNavigatorFragment) = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { Triple(ui.prefs.scroll, ui.chapterHref, ui.prefs) }.collectLatest { (scrolling, href, _) ->
                if (!scrolling || href == null) return@collectLatest
                delay(600)          // let the new chapter lay out before measuring it
                pollScroll(n)
            }
        }
    }

    private suspend fun pollScroll(n: EpubNavigatorFragment) {
        // Use the navigator's evaluateJavascript(); a view-tree walk for a WebView could sample a
        // neighbouring chapter the pager has preloaded, and holds a strong reference on the Activity.
        val raw = runCatching {
            n.evaluateJavascript(
                "Math.round(window.scrollY)+','+Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0)+','+window.innerHeight",
            )
        }.getOrNull() ?: return
        val p = raw.trim('"').split(',')
        if (p.size != 3) return
        val y = p[0].toDoubleOrNull() ?: return
        val sh = p[1].toDoubleOrNull() ?: return
        val ih = p[2].toDoubleOrNull() ?: return
        // Sets canScrollChapter, which the tap handler reads. It never advances the chapter by itself.
        canScrollChapter = sh > ih + 4
        if (y > 0 && !loggedScroll) {
            loggedScroll = true
            android.util.Log.i("Kairn", "scroll JS works: y=$y sh=$sh ih=$ih")
        }
    }

    /** R-07: real chapter length (word count), so the time left is an honest estimate, not a guess. */
    private fun loadChapterWords(loc: Locator) = lifecycleScope.launch {
        val p = pub ?: return@launch
        val href = loc.href.toString()
        val words = withContext(Dispatchers.IO) {          // reads + tokenises a whole chapter: not for the UI thread
            runCatching { p.content(loc)?.text() }.getOrNull()?.let { t ->
                t.trim().split(wordSplit).count { w -> w.any { it.isLetterOrDigit() } }
            }
        }
        // Feed the book-wide pace once per chapter. A result for a chapter we already left still counts:
        // it is a real measurement, and the pace is about the book, not about where the reader is now.
        if (words != null && measuredChapters.add(href)) ui.pace = ui.pace.add(words, positionsPerHref[href] ?: 0)
    }

    /** One search at a time: typing cancels the previous query so results cannot land out of order. */
    override fun search(q: String) {
        ui.lastQuery = q
        ui.searchCap = 100
        runSearch(q)
    }

    /** "Show more": the sheet asks for another page, so the search runs again with a wider cap. */
    override fun searchMore() {
        if (ui.lastQuery.length < 3) return
        ui.searchCap += 100
        runSearch(ui.lastQuery)
    }

    private fun runSearch(q: String) {
        searchJob?.cancel()
        if (q.length < 3) { ui.searchResults = emptyList(); ui.searchBusy = false; return }
        val cap = ui.searchCap
        searchJob = lifecycleScope.launch {
            delay(250)
            val p = pub ?: return@launch
            ui.searchBusy = true
            // Publication.search() walks the whole book: on the main dispatcher it freezes the reader
            // for as long as the scan takes. The iterator is used and closed off the UI thread.
            val hits = withContext(Dispatchers.IO) {
                runCatching {
                    val iter = p.search(q) ?: return@runCatching emptyList<Locator>()
                    try {
                        val out = mutableListOf<Locator>()
                        while (out.size < cap) {
                            val page = iter.next().getOrNull() ?: break    // Try<LocatorCollection, SearchError>
                            out += page.locators
                        }
                        out
                    } finally { iter.close() }
                }.getOrDefault(emptyList())
            }
            ui.searchBusy = false
            ui.searchResults = hits
        }
    }
}
