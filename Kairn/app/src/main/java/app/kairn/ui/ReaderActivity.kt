@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.kairn.ui

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.isActive
import org.readium.r2.navigator.preferences.FontFamily
import android.os.SystemClock
import android.util.Log
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
import app.kairn.ui.theme.readerUi
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
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import java.util.UUID
import org.readium.r2.navigator.preferences.Color as RColor
import org.readium.r2.navigator.preferences.TextAlign as RTextAlign

/** The navigator, the input events and the debounced save; everything drawn is ReaderScreen. */
class ReaderActivity : AppCompatActivity(), ReaderActions {
    companion object {
        const val EXTRA_ID = "book_id"
        const val EXTRA_LOCATOR = "locator"          // a search hit opens the book at that place
        private const val PAGE_SIZE = 100
    }

    private val app get() = application as KairnApp
    private lateinit var bookId: String
    /** A search hit's locator: where to open instead of the saved position. */
    private var startLocator: String? = null
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
    /**
     * Reads the chapter aloud. Tapping play speaks from the reader's own position; when a chapter runs out
     * the page turns and the next one starts, which is also what happens if the reader turns it themselves.
     */
    private val tts by lazy {
        ReaderTts(
            this,
            onStarted = { ui.ttsStarting = false },
            onEnded = { ui.ttsStarting = false; advanceWithTts() },
            onFailed = {
                ui.ttsOn = false; ui.ttsStarting = false
                Toast.makeText(this, getString(R.string.s_tts_unavailable), Toast.LENGTH_LONG).show()
            },
            onWord = ::followVoice,
        )
    }

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
        // No restored fragment (there is no factory); the reader reopens from the saved position. R-09
        super.onCreate(null)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bookId = intent.getStringExtra(EXTRA_ID) ?: return finish()
    startLocator = intent.getStringExtra(EXTRA_LOCATOR)
        // Held as state: the manifest handles uiMode itself, so a day/night switch must land here.
        ui.night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        container = FragmentContainerView(this).apply { id = View.generateViewId() }
        val root = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            setContent {
                // collectAsState, not .value: a StateFlow read in composition is not observed.
                val s by app.store.settings.collectAsState()
                KairnTheme(glass = s.glass, ambient = s.ambient) {
                    ReaderScreen(
                        ui = ui,
                        colors = readerUi(effectiveTheme(ui.prefs, s.autoSwitch), ui.prefs.pageColor, ui.prefs.inkColor),
                        isTablet = isTablet, toast = toast, act = this@ReaderActivity, wpm = s.readingWpm,
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
            // A cold "Open with" can arrive before the first library read finishes: wait, then retry.
            app.store.awaitLoaded()
            val late = app.store.get(bookId) ?: return@launch finish()
            applyBook(late)
            openBook(late)
        }
        // Bind text-to-speech while the book is already open: the engine's cold bind is seconds long, and
        // paying for it inside the tap is what made "Read aloud" read as a dead button.
        lifecycleScope.launch { delay(1200); tts.warm() }
    }

    /** The parts of the reader that come straight from the stored record. */
    private fun applyBook(book: Book) {
        ui.bookTitle = book.title
        ui.bookWords = book.words                      // cached: no counting at all on a second open
        ui.prefs = book.prefs ?: app.store.settings.value.defaults
        ui.marks = book.marks
        if (app.store.settings.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private suspend fun openBook(book: Book) {
        val t0 = SystemClock.elapsedRealtime()
        // Readium's AssetRetriever/PublicationOpener reference no dispatcher, so parsing must hop to IO.
        val p = withContext(Dispatchers.IO) { app.readium.open(android.net.Uri.parse(book.uri)) }
        if (p == null) {                                    // N-04: message, never a crash
            writer.markBroken(); ui.error = "bad"; ui.loading = false; return
        }
        pub = p
        ui.pub = p                    // state: Contents and the seek fallback read it during composition
        rtl = p.metadata.readingProgression == ReadingProgression.RTL
        // positions() scans the whole publication: the slow part of opening, so IO, never the main thread.
        val tp = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val computed = withContext(Dispatchers.IO) { runCatching { p.positions() }.getOrDefault(emptyList()) }
            ui.positions = computed
            positionsPerHref = computed.groupingBy { it.href.toString() }.eachCount()   // the whole-book time estimate needs this share
            measurePending()                                                            // a chapter measured before this arrived
            android.util.Log.i("KairnPerf", "positions n=${ui.positions.size} ${SystemClock.elapsedRealtime() - tp}ms")
            // Import already counted the words. A record imported before that existed is counted once here.
            if (ui.bookWords == 0) {
                val t0w = SystemClock.elapsedRealtime()
                val words = withContext(Dispatchers.IO) { app.readium.countWords(p) }
                if (words > 0) {
                    ui.bookWords = words
                    writer.saveWords(words)
                }
                android.util.Log.i("KairnPerf", "words=$words ${SystemClock.elapsedRealtime() - t0w}ms (counted on open)")
            }
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
        // Palette order in Tokens.kt decides these labels.
        val colourNames = listOf(
            R.string.s_colour_amber, R.string.s_colour_mint, R.string.s_colour_rose,
            R.string.s_colour_blue, R.string.s_colour_violet, R.string.s_colour_sand,
        )
        val colourLabels = K.highlightColors.zip(colourNames).map { (pair, name) -> getString(name) to pair.second.toArgb() }
        val highlightCallback = HighlightActionModeCallback(
            colourLabels,
            copyLabel = getString(R.string.s_menu_copy),
            noteLabel = getString(R.string.s_menu_note),
            shareLabel = getString(R.string.s_menu_share),
            lookupLabel = getString(R.string.s_menu_look_up),
            onCopy = { copySelection() },
            // All named: a trailing lambda now binds to onLookup, which is the last parameter.
            onHighlight = { c, done -> highlightSelection(c) { done() } },
            onNote = { done -> startNote(done) },
            onShare = { done -> shareSelection(done) },
            onLookup = { done -> lookUpSelection(done) },
        )
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = (startLocator ?: book.locator)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() },
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
                loc.locations.progression?.let { ui.chapterProgress = it.toFloat() }
                ui.chapterTitle = loc.title ?: ""
                if (loc.href.toString() != ui.chapterHref) {
                    ui.chapterHref = loc.href.toString()
                    loadChapterWords(loc)
                    if (ui.ttsOn && !ui.ttsStarting) speakChapter(loc)   // follow the reader into the next chapter
                }
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

    /** configChanges covers uiMode/orientation, so the Activity is not recreated: re-apply here. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        applyPrefs()
    }

    override fun onResume() {
        super.onResume()
        sessionStart = SystemClock.elapsedRealtime()      // DS-02: a reading session starts when the book shows
    }

    override fun onPause() {
        super.onPause()
        tts.stop()                                   // never reads on in the background
        ui.ttsOn = false; ui.ttsStarting = false; ui.ttsPaused = false
        clearFollow()
        stopAutoScroll()
        // The prefs write is debounced: a close inside that window would drop the last change.
        writer.savePrefs(ui.prefs)
        nav?.currentLocator?.value?.let(::savePosition)
        commitSession()
    }
    override fun onDestroy() { closeSearch(); tts.shutdown(); pub?.close(); super.onDestroy() }

    /**
     * DS-02: adds the time this session lasted to today's minutes. A glance at a page is not a session, so
     * anything under a minute is dropped - the file is a reading record, not a launch counter.
     */
    private fun commitSession() {
        val started = sessionStart
        sessionStart = 0L
        if (started == 0L) return
        val minutes = ((SystemClock.elapsedRealtime() - started) / 60_000L).toInt()
        if (minutes < 1) return
        app.store.saveStats { it.add(java.time.LocalDate.now().toString(), minutes) }
    }

    // ---- sleep timer (R-15) ----
    private var sleepJob: Job? = null
    private var sessionStart = 0L                       // DS-02: when this reading session began

    /**
     * Stops reading aloud after [minutes], or at the end of the chapter when [minutes] is negative, and off
     * at 0. Reading in bed is the one use of read-aloud where the thing that matters is that it stops.
     */
    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        ui.sleepChoice = minutes
        when {
            minutes == 0 -> return
            minutes < 0 -> toast.show(getString(R.string.toast_sleep_chapter))
            else -> {
                toast.show(resources.getQuantityString(R.plurals.reading_stops_in, minutes, minutes))
                sleepJob = lifecycleScope.launch {
                    delay(minutes * 60_000L)
                    endSleep()
                }
            }
        }
    }

    /** The timer fired (or the chapter ended under an end-of-chapter timer): stop the voice, keep the page. */
    private fun endSleep() {
        sleepJob?.cancel()
        sleepJob = null
        val wasReading = ui.ttsOn
        ui.sleepChoice = 0
        if (wasReading) {
            tts.stop()
            ui.ttsOn = false; ui.ttsStarting = false; ui.ttsPaused = false
            clearFollow()
        }
        toast.show(getString(R.string.toast_sleep_done))
    }

    // ---- preferences (R-03, R-04, R-05, R-06, R-10) ----

    /** Tablet = the OS threshold (600dp shortest side). Two-page columns are a wide-screen idea. */
    private val isTablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

    private fun effectiveTheme(p: ReaderPrefs, autoSwitch: Boolean): ThemeId {
        if (!autoSwitch) return p.theme
        val night = ui.night
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
        val t = effectiveTheme(p, app.store.settings.value.autoSwitch)
        // BLACK and GRAY are not Readium themes: they are explicit page/ink colours laid over a base theme.
        // GRAY is charcoal (8.5:1 for body text), so it sits on the DARK base, like BLACK does.
        // A custom page/ink pair from the Display sheet wins over both.
        val bg = p.pageColor?.let { RColor(it) } ?: when (t) {
            ThemeId.BLACK -> RColor(0xFF000000.toInt())
            ThemeId.GRAY -> RColor(0xFF3A4045.toInt())
            else -> null
        }
        val ink = p.inkColor?.let { RColor(it) } ?: when (t) {
            ThemeId.BLACK -> RColor(0xFFDADDE0.toInt())
            ThemeId.GRAY -> RColor(0xFFE4E8EB.toInt())
            else -> null
        }
        return EpubPreferences(
            theme = when (t) {   // exhaustive: BLACK/GRAY carry their colours explicitly below
                ThemeId.LIGHT -> Theme.LIGHT
                ThemeId.SEPIA -> Theme.SEPIA
                ThemeId.DARK, ThemeId.BLACK, ThemeId.GRAY -> Theme.DARK
            },
            backgroundColor = bg,
            textColor = ink,
            fontSize = p.fontScale, lineHeight = p.lineHeight, paragraphSpacing = p.paragraphSpacing,
            pageMargins = p.margins, publisherStyles = false, scroll = p.scroll,
            columnCount = if (p.twoPage && isTablet) ColumnCount.TWO else null,      // R-14: reflowable two-page, tablet only. `spread` is fixed-layout only; setting it here crashes.
            textAlign = if (p.justify) RTextAlign.JUSTIFY else RTextAlign.START, hyphens = p.hyphens,
            fontFamily = p.fontFamily?.let { FontFamily(it) },   // a CSS family the WebView resolves
        )
    }

    // ---- auto scroll ----
    // One JS interval moves the page inside the WebView: a per-frame Kotlin round-trip would be a wake-up
    // and a hop across the bridge every 16 ms. The loop below only watches for the end of the chapter.
    private var autoJob: Job? = null
    private var autoHold: Job? = null
    private var autoHeld = false                 // a finger is on the page: no drift, and no page turn

    private fun autoStep() = (ui.prefs.scrollSpeed * resources.displayMetrics.density / 50f).coerceAtLeast(0.5f)
    private fun autoJs(step: Float) = "window.__kairnAuto && clearInterval(window.__kairnAuto);" +
        "window.__kairnAuto = setInterval(function(){ window.scrollBy(0, $step) }, 20)"

    override fun toggleAutoScroll() {
        if (autoJob != null) { stopAutoScroll(); return }
        if (!ui.prefs.scroll) return                 // the toggle is only shown in scroll mode
        ui.autoScrolling = true
        autoJob = lifecycleScope.launch {
            val n = nav ?: return@launch
            n.evaluateJavascript(autoJs(autoStep()))
            var last = -1f
            var still = 0
            while (isActive) {
                delay(1500)
                if (autoHeld) { last = -1f; still = 0; continue }   // held still by a finger, not by the bottom
                val y = n.evaluateJavascript("window.scrollY")?.toFloatOrNull() ?: -1f
                still = if (y == last) still + 1 else 0
                last = y
                if (still >= 2) { still = 0; n.goForward(true) }     // the bottom: turn and keep going
            }
        }
    }

    /** A finger on the page stops the drift so the reader can look at something. */
    private fun holdAutoScroll() {
        if (autoJob == null || autoHeld) return
        autoHeld = true
        lifecycleScope.launch { nav?.evaluateJavascript("clearInterval(window.__kairnAuto)") }
    }

    /** It comes back a moment after the finger leaves, so a tap does not restart it under the next tap. */
    private fun releaseAutoScroll() {
        if (autoJob == null || !autoHeld) return
        autoHold?.cancel()
        autoHold = lifecycleScope.launch {
            delay(700)
            autoHeld = false
            nav?.evaluateJavascript(autoJs(autoStep()))
        }
    }

    private fun stopAutoScroll() {
        autoJob?.cancel()
        autoJob = null
        autoHold?.cancel()
        autoHold = null
        autoHeld = false
        ui.autoScrolling = false
        lifecycleScope.launch { nav?.evaluateJavascript("clearInterval(window.__kairnAuto)") }
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
        if (!ui.prefs.scroll) stopAutoScroll()       // auto scroll only exists in scroll mode
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

    // Scroll mode: the pager pages on a sideways wobble, so a long horizontal drag is handled here.
    private var downX = 0f; private var downY = 0f; private var armed = false; private var tracking = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Before the scroll-mode gate: holding the page must stop the drift in any mode it can run in.
        if (autoJob != null) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> holdAutoScroll()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAutoScroll()
        }
        if (!ui.prefs.scroll || ui.sheet != ReaderSheet.NONE) return super.dispatchTouchEvent(ev)

        // The threshold comes from Settings > Reading > "Swipe distance".
        val flick = app.store.settings.value.swipeThreshold * resources.displayMetrics.density
        val swipeOn = app.store.settings.value.swipeChapters
        // Only a touch that STARTS on the book may page it. The bars are Compose content in this same
        // window, so without this a horizontal drag on the seek slider armed the swipe, swallowed the
        val inReadingArea = ev.y < ui.chromeTopPx
        fun flicks(dx: Float, dy: Float) = kotlin.math.abs(dx) > flick && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 3f
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; armed = false; tracking = inReadingArea }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && !armed && flicks(ev.x - downX, ev.y - downY)) armed = true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                val wasArmed = armed; armed = false; val wasTracking = tracking; tracking = false
                if (wasTracking && (wasArmed || flicks(dx, dy))) {
                    if (swipeOn) {
                        // Left drag shows the next chapter; right drag the previous one.
                        if (dx < 0) nav?.goForward(true) else nav?.goBackward(true)
                    }
                    return true                                  // consumed: the pager never sees it
                }
            }
            MotionEvent.ACTION_CANCEL -> { armed = false; tracking = false }
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

    /**
     * The selection as plain text, read while the ActionMode is still open: finishing the mode clears the
     * WebView selection, which is why every caller takes its `done` and closes the menu afterwards.
     */
    private suspend fun selectionText(): String? = nav
        ?.let { n -> runCatching { n.currentSelection() }.getOrNull()?.locator?.text?.highlight }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Share hands the quote to the system chooser as plain text. Kairn never picks the app itself. */
    private fun shareSelection(done: () -> Unit) = lifecycleScope.launch {
        val text = selectionText()
        runCatching { nav?.clearSelection() }
        done()
        if (text == null) return@launch
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        runCatching { startActivity(android.content.Intent.createChooser(send, getString(R.string.s_share_selection))) }
    }

    /**
     * Look up hands the word to whatever dictionary the reader has, through ACTION_PROCESS_TEXT: no dictionary
     * is bundled, nothing is sent anywhere by Kairn, and an app with no dictionary installed says so.
     */
    private fun lookUpSelection(done: () -> Unit) = lifecycleScope.launch {
        val text = selectionText()?.replace(Regex("\\s+"), " ")?.take(64)
        runCatching { nav?.clearSelection() }
        done()
        if (text == null) return@launch
        val process = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        // resolveActivity needs the <queries> entry in the manifest on Android 11+, which the manifest has.
        if (packageManager.resolveActivity(process, 0) == null) {
            toast.show(getString(R.string.s_no_dictionary_app))
            return@launch
        }
        runCatching { startActivity(android.content.Intent.createChooser(process, getString(R.string.s_look_up))) }
    }

    /** A-02: add a highlight for the current selection. [done] runs once the mark is saved. */
    private fun highlightSelection(color: Int, done: () -> Unit = {}) = lifecycleScope.launch {
        val n = nav ?: return@launch
        val sel = runCatching { n.currentSelection() }.getOrNull() ?: return@launch
        addMarkTo(Mark(UUID.randomUUID().toString(), "highlight", color, sel.locator.text.highlight ?: "", "", sel.locator.toJSON().toString(), System.currentTimeMillis()))
        n.clearSelection()
        done()
    }

    /** Returns whether a mark was actually written, so the toast cannot announce a failed write. */
    private fun addMark(kind: String, color: Int, onDone: (Boolean) -> Unit = {}) = lifecycleScope.launch {
        val n = nav ?: return@launch onDone(false)
        if (kind == "bookmark") {
            val loc = n.currentLocator.value
            addMarkTo(Mark(UUID.randomUUID().toString(), kind, color, loc.title ?: "", "", loc.toJSON().toString(), System.currentTimeMillis()))
            onDone(true)
        } else {
            val sel = runCatching { n.currentSelection() }.getOrNull() ?: return@launch onDone(false)   // the other two sites guard this experimental API
            addMarkTo(Mark(UUID.randomUUID().toString(), kind, color, sel.locator.text.highlight ?: "", "", sel.locator.toJSON().toString(), System.currentTimeMillis()))
            n.clearSelection()
            onDone(true)
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
            val added = getString(R.string.toast_bookmark_added)
            addMark("bookmark", K.mint.toArgb()) { ok -> if (ok) toast.show(added) }
        } else {
            writer.removeBookmarksIn(h, ::markHref)
            toast.show(getString(R.string.toast_bookmark_removed))
        }
    }

    /** Play / pause / resume the chapter being read aloud (or read from the current position). */
    override fun toggleTts() {
        when {
            ui.ttsOn && ui.ttsPaused -> { ui.ttsPaused = false; ui.ttsStarting = false; tts.resume() }
            ui.ttsOn -> { ui.ttsPaused = true; tts.pause() }
            else -> {
                val loc = nav?.currentLocator?.value ?: return
                // Show the stop state on the tap, not when the engine finally speaks: a cold bind is seconds
                // long and an unchanged button reads as a dead tap.
                ui.ttsOn = true; ui.ttsStarting = true; ui.ttsPaused = false
                speakChapter(loc)
            }
        }
    }

    override fun stopTts() {
        tts.stop()
        ui.ttsOn = false; ui.ttsStarting = false; ui.ttsPaused = false
        ui.sheet = ReaderSheet.NONE
        clearFollow()
        // The timer belongs to the voice: a manual stop ends the timer too, instead of leaving a chip that
        // promises to stop something that is already stopped.
        sleepJob?.cancel(); sleepJob = null
        ui.sleepChoice = 0
    }

    /** Drop the painted sentence and the page the voice had reached. */
    private fun clearFollow() {
        lastSentence = -1
        followedPage = -1
        lifecycleScope.launch { nav?.evaluateJavascript(ReaderFollow.clear()) }
    }

    override fun setSpeechRate(rate: Float) {
        updatePrefs(ui.prefs.copy(speechRate = rate.coerceIn(0.6f, 2f)))
        tts.setRate(ui.prefs.speechRate)
    }

    private fun speakChapter(loc: Locator) = lifecycleScope.launch {
        val p = pub ?: return@launch
        val text = withContext(Dispatchers.IO) { runCatching { p.content(loc)?.text() }.getOrNull() }
        if (text.isNullOrBlank()) {
            ui.ttsOn = false; ui.ttsStarting = false
            Toast.makeText(this@ReaderActivity, getString(R.string.s_tts_chapter_failed), Toast.LENGTH_LONG).show()
            return@launch
        }
        val from = ReaderLogic.ttsStartOffset(text, loc.locations.progression?.toFloat() ?: 0f)
        spokenText = text
        spokenHref = loc.href.toString()
        lastSentence = -1
        followedPage = -1
        tts.speak(text.substring(from), ui.prefs.speechRate, from)
    }

    // ---- follow the voice ----
    private var spokenText = ""
    private var spokenHref: String? = null
    private var lastSentence = -1
    private var followedPage = -1
    private var followAt = 0L

    /**
     * The voice reports each word's place in the chapter. Paint the sentence it is in, and in page mode turn
     * the page when it moves on: a page cannot scroll to follow a voice the way a scroll view can.
     */
    private fun followVoice(at: Int) {
        if (!ui.ttsOn || ui.ttsPaused || spokenText.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - followAt < 300) return
        followAt = now
        val (s, e) = ReaderLogic.sentenceAt(spokenText, at)
        if (s != lastSentence) {
            lastSentence = s
            lifecycleScope.launch { nav?.evaluateJavascript(ReaderFollow.highlight(s, e)) }
        }
        if (ui.prefs.scroll) return                     // a scroll view already shows the spoken place
        val href = spokenHref ?: ui.chapterHref ?: return
        val chapter = ui.positions.filter { it.href.toString() == href }
        if (chapter.size < 2) return
        val progress = at.toFloat() / spokenText.length.coerceAtLeast(1)
        val idx = chapter.indexOfLast { (it.locations.progression ?: 0.0) <= progress }.coerceAtLeast(0)
        if (idx == followedPage) return
        followedPage = idx
        Log.i("KairnPerf", "tts follow page $idx of ${chapter.size} at ${(progress * 100).toInt()}%")
        lifecycleScope.launch { nav?.go(chapter[idx], animated = false) }
    }

    /** The chapter ended by itself: turn the page and keep reading, unless this was the last one. */
    private fun advanceWithTts() {
        // A timer set to "end of chapter" is fulfilled here, before the next chapter can start.
        if (ui.sleepChoice < 0) { endSleep(); return }
        val n = nav ?: run { ui.ttsOn = false; return }
        val here = n.currentLocator.value?.href?.toString()
        val last = pub?.readingOrder?.lastOrNull()?.href?.toString()
        if (here == null || here == last) { ui.ttsOn = false; return }
        lifecycleScope.launch { n.goForward(true) }
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
        if (words != null) {
            if (href == ui.chapterHref) ui.chapterWords = words      // the chapter on screen, for its own time left
            val positionsHere = positionsPerHref[href] ?: 0
            // positions() lands AFTER the first chapter is read, so hold the word count until it does
            // instead of measuring against zero (which silently dropped the chapter for good).
            if (positionsHere < ReaderLogic.MIN_CHAPTER_POSITIONS) pendingChapter = href to words
            else if (measuredChapters.add(href)) ui.pace = ui.pace.add(words, positionsHere)
        }
    }

    /** A chapter read before positions() finished, waiting for its share of the book. */
    private var pendingChapter: Pair<String, Int>? = null

    private fun measurePending() {
        val pending = pendingChapter ?: return
        val positionsHere = positionsPerHref[pending.first] ?: return
        if (positionsHere < ReaderLogic.MIN_CHAPTER_POSITIONS) return
        pendingChapter = null
        if (measuredChapters.add(pending.first)) ui.pace = ui.pace.add(pending.second, positionsHere)
    }

    /** The live iterator of the current query, so "show more" pulls the next page instead of rescanning. */
    @Volatile private var searchIter: SearchIterator? = null

    private fun closeSearch() {
        val it = searchIter ?: return
        searchIter = null
        runCatching { it.close() }
    }

    /** One search at a time: typing cancels the previous query so results cannot land out of order. */
    override fun search(q: String) {
        searchJob?.cancel()
        closeSearch()
        ui.lastQuery = q
        ui.searchResults = emptyList()
        ui.searchMoreAvailable = false
        if (q.length < 3) { ui.searchBusy = false; return }
        searchJob = lifecycleScope.launch {
            delay(250)
            val p = pub ?: return@launch
            ui.searchBusy = true
            // Publication.search() walks the whole book: on the main dispatcher it freezes the reader
            // for as long as the scan takes. The iterator is created and pulled off the UI thread.
            val page = withContext(Dispatchers.IO) {
                runCatching {
                    val iter = p.search(q)
                    if (iter == null) emptyList<Locator>() to false
                    else { searchIter = iter; pull(iter, PAGE_SIZE) }
                }.getOrNull()
            }
            ui.searchBusy = false
            if (page == null) return@launch
            ui.searchResults = page.first
            ui.searchMoreAvailable = page.second
        }
    }

    /** "Show more": the next page from the SAME iterator, not a fresh scan of the whole book. */
    override fun searchMore() {
        val iter = searchIter ?: return
        if (ui.searchBusy || ui.lastQuery.length < 3) return
        searchJob = lifecycleScope.launch {
            ui.searchBusy = true
            val page = withContext(Dispatchers.IO) { runCatching { pull(iter, PAGE_SIZE) }.getOrNull() }
            ui.searchBusy = false
            if (page == null) return@launch
            ui.searchResults = ui.searchResults + page.first
            ui.searchMoreAvailable = page.second
        }
    }

    /**
     * Pulls up to [want] hits. Second = whether the page came back FULL, which is the only evidence that
     * more hits may exist (a short page is the end of the book, not a cap).
     * Bounded by collections as well as hits: an iterator answering with empty pages must not spin.
     */
    private suspend fun pull(iter: SearchIterator, want: Int): Pair<List<Locator>, Boolean> {
        val out = mutableListOf<Locator>()
        var rounds = 0
        while (out.size < want && rounds < 64) {
            rounds++
            val page = iter.next().getOrNull() ?: return out to false    // Try<LocatorCollection, SearchError>
            if (page.locators.isEmpty()) continue
            out += page.locators
        }
        return out to (out.size >= want)
    }

}
