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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.ui.unit.sp
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
import app.kairn.data.*
import app.kairn.ui.kit.*
import app.kairn.ui.screens.AnnotationsContent
import app.kairn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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

private enum class Sheet { NONE, DISPLAY, CONTENTS, SEARCH, NOTES }

class ReaderActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "book_id" }

    private val app get() = application as KairnApp
    private lateinit var bookId: String
    private var pub: Publication? = null
    private var nav: EpubNavigatorFragment? = null
    private lateinit var container: FragmentContainerView

    // UI state
    private var bars by mutableStateOf(true)
    private var sheet by mutableStateOf(Sheet.NONE)
    private var prefs by mutableStateOf(ReaderPrefs())
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
    private var footnote by mutableStateOf<String?>(null)
    private var jumpBack by mutableStateOf<Locator?>(null)
    private var chapterTitle by mutableStateOf("")
    private var bookTitle by mutableStateOf("")
    private var marks by mutableStateOf<List<Mark>>(emptyList())
    private var searchResults by mutableStateOf<List<Locator>>(emptyList())
    private var rtl = false
    private var canScrollChapter = false
    private var loggedScroll = false
    private var positions: List<Locator> = emptyList()   // R-07: fine-grained seek targets
    private var positionsPerHref: Map<String, Int> = emptyMap()   // how many of those belong to each chapter
    private var chapterTitles: Map<String, String> = emptyMap()   // href -> chapter title (for scrubbing)
    private var chapterWords by mutableIntStateOf(0)      // R-07: words in the current chapter (state: the time-left label reads it)
    private var chapterHref by mutableStateOf<String?>(null)   // state: the bookmark button reads it to know which chapter it is in
    private var bookProgress by mutableFloatStateOf(0f)   // state: WHOLE-BOOK progression; the slider and time-left label read it
    private var searchJob: Job? = null
    private val wordSplit = Regex("\\s+")
    private val toast = ToastState()                     // short confirmations ("Bookmark added")

    private val exportNotes = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { u ->
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
                KairnTheme(glass = s.glass, ambient = s.ambient) { Overlay() }
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
        bookTitle = book.title
        prefs = book.prefs ?: app.store.settings.value.defaults
        marks = book.marks
        if (app.store.settings.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private suspend fun openBook(book: Book) {
        val t0 = SystemClock.elapsedRealtime()
        // Parsing the EPUB is blocking and this coroutine runs on the main dispatcher: Readium's
        // AssetRetriever and PublicationOpener reference no dispatcher, so this must hop to IO (as
        // positions() below does).
        val p = withContext(Dispatchers.IO) { app.readium.open(android.net.Uri.parse(book.uri)) }
        if (p == null) {                                    // N-04: message, never a crash
            app.store.put(book.copy(broken = true)); error = "bad"; loading = false; return
        }
        pub = p
        rtl = p.metadata.readingProgression == ReadingProgression.RTL
        // Load the whole-book position list for the seek slider. positions() scans the whole
        // publication and is the slow part of opening: it belongs on IO, never the main thread.
        val tp = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val computed = withContext(Dispatchers.IO) { runCatching { p.positions() }.getOrDefault(emptyList()) }
            positions = computed
            positionsPerHref = computed.groupingBy { it.href.toString() }.eachCount()   // the whole-book time estimate needs this share
            android.util.Log.i("KairnPerf", "positions n=${positions.size} ${SystemClock.elapsedRealtime() - tp}ms")
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
            chapterTitles = map
        }

        val factory = EpubNavigatorFactory(p)
        val highlightCallback = HighlightActionModeCallback(
            K.highlightColors.map { (n, c) -> n to c.toArgb() },
            onCopy = { copySelection() },
        ) { c, done -> highlightSelection(c) { done() } }
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() },
            initialPreferences = toEpub(prefs),
            listener = object : EpubNavigatorFragment.Listener {
                override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
                    if (context is HyperlinkNavigator.FootnoteContext) {   // R-08: footnote pop-up
                        footnote = HtmlCompat.fromHtml(context.noteContent, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
                        return false
                    }
                    jumpBack = nav?.currentLocator?.value                  // R-08: remember where we were
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
                if (prefs.scroll) {
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
        // fires, and WebView.getScrollY() is always 0 (Chromium scrolls internally). Read
        // window.scrollY via evaluateJavascript, which is what this watcher polls in scroll mode.
        startScrollWatcher(n)

        // Timing: how long the whole open took (parse + navigator + first position).
        android.util.Log.i("KairnPerf", "openBook ${SystemClock.elapsedRealtime() - t0}ms title='${book.title.take(32)}'")

        // R-09: save the position, debounced. The UI state follows every locator immediately; only
        // the disk write waits. collectLatest cancels the pending write when the next locator
        // arrives: a write lands ~0.8 s after the reader settles, with a 5 s ceiling during
        // continuous scroll.
        lifecycleScope.launch {
            var lastSaved = 0L
            n.currentLocator.collectLatest { loc ->
                if (loading) loading = false            // first real position: reveal the page
                // WHOLE-BOOK progression, not the chapter's: the slider and the time left are about
                // the book (progression is the position inside the current resource, not the book).
                loc.locations.totalProgression?.let { bookProgress = it.toFloat() }
                chapterTitle = loc.title ?: ""
                if (loc.href.toString() != chapterHref) { chapterHref = loc.href.toString(); chapterWords = 0; loadChapterWords(loc) }
                if (jumpBack?.href == loc.href && jumpBack?.locations?.progression == loc.locations.progression) jumpBack = null
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
                marks = m
                n.applyDecorations(m.filter { it.kind != "bookmark" }.mapNotNull { mk ->
                    runCatching { Decoration(mk.id, Locator.fromJSON(JSONObject(mk.locator))!!, Decoration.Style.Highlight(tint = mk.color)) }.getOrNull()
                }, "marks")
            }
        }
        applyPrefs()
        applyWindow()
    }

    /** Cheap on the caller's thread: Store updates memory and queues the disk write on its own thread. */
    private fun savePosition(loc: Locator) {
        val atEnd = (loc.locations.totalProgression ?: 0.0) >= 0.99     // the last position of the book
        app.store.update(bookId) {
            it.copy(locator = loc.toJSON().toString(), progress = (loc.locations.totalProgression ?: 0.0).toFloat(),
                lastRead = System.currentTimeMillis(), broken = false,
                status = when {
                    // Reaching the end IS finishing the book: nothing else sets FINISHED.
                    atEnd -> Status.FINISHED
                    it.status == Status.NONE || it.status == Status.WANT -> Status.READING
                    else -> it.status          // a manual status (and a re-read of a finished book) is left alone
                })
        }
    }

    override fun onPause() { super.onPause(); nav?.currentLocator?.value?.let(::savePosition) }
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

    private fun updatePrefs(p: ReaderPrefs) {
        prefs = p
        app.store.update(bookId) { it.copy(prefs = p) }     // R-10: per book
        applyPrefs()
    }

    private fun applyPrefs() {
        nav?.submitPreferences(toEpub(prefs))
        window.attributes = window.attributes.also { it.screenBrightness = if (prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness }
    }

    // ---- window (R-11) ----

    private fun toggleBars() { bars = !bars; applyWindow() }
    private fun applyWindow() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (app.store.settings.value.fullscreen && !bars) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    // R-07: volume-key page turns
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (app.store.settings.value.volumeKeys && nav != null) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { nav!!.goBackward(true); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { nav!!.goForward(true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (app.store.settings.value.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) true
        else super.onKeyUp(keyCode, event)

    // Scroll-mode horizontal gestures. The pager's own threshold is too eager: a small sideways
    // wobble while scrolling would jump a chapter. So in scroll mode we detect a deliberate flick
    // ourselves and swallow anything shorter, so the pager never sees it either.
    private var downX = 0f; private var downY = 0f; private var armed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!prefs.scroll) return super.dispatchTouchEvent(ev)

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
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Kairn", text))
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
    private fun addMarkTo(m: Mark) = app.store.update(bookId) { it.copy(marks = it.marks + m) }

    /** The chapter a mark's locator lives in, for the once-per-chapter bookmark rule. */
    private fun markHref(m: Mark): String? =
        runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull()?.href?.toString()

    /**
     * One bookmark per chapter: the top-bar button bookmarks the chapter you are in, and pressing it
     * again removes every bookmark in that chapter.
     */
    private fun toggleChapterBookmark(bookmarked: Boolean) {
        val h = chapterHref ?: return
        if (!bookmarked) {
            addMark("bookmark", K.mint.toArgb())
            toast.show("Bookmark added")
        } else {
            app.store.update(bookId) { b -> b.copy(marks = b.marks.filterNot { it.kind == "bookmark" && markHref(it) == h }) }
            toast.show("Bookmark removed")
        }
    }

    private fun goTo(l: Locator) { nav?.go(l, true); sheet = Sheet.NONE }

    /**
     * R-03: watches the chapter's scroll position (via JavaScript, because Readium's WebView does
     * not report scroll through the Android listener API) to know when a scrollable chapter ends.
     * It does not advance by itself.
     */
    private fun startScrollWatcher(n: EpubNavigatorFragment) = lifecycleScope.launch {
        // Runs only while RESUMED and only while scroll mode is on (no 250 ms wake-ups otherwise).
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { prefs.scroll }.collectLatest { scrolling ->
                if (!scrolling) return@collectLatest
                while (true) { delay(250); pollScroll(n) }
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
        if (words != null && chapterHref == href) chapterWords = words    // ignore a result for a chapter we already left
    }

    /** R-07: "12 min left" for the WHOLE book, at a 240 wpm reading speed. Empty when we can't know. */
    private fun timeLeft(bookProgression: Float): String {
        if (chapterWords <= 0) return ""
        val chapterPositions = chapterHref?.let { positionsPerHref[it] } ?: 0
        val totalPositions = positions.size
        if (chapterPositions <= 0 || totalPositions <= 0) return ""
        // Readium splits a chapter into positions of even character size, so words-per-position
        // measured on the current chapter scales to the rest of the book. Remaining positions x
        // that is the book's time left.
        val wordsPerPosition = chapterWords.toFloat() / chapterPositions
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * totalPositions * wordsPerPosition).toInt()
        val mins = remaining / 240
        return when {
            remaining <= 0 -> ""
            mins <= 0 -> "under a min left"
            mins < 60 -> "$mins min left"
            else -> "${mins / 60} h ${mins % 60} min left"
        }
    }

    /** One search at a time: typing cancels the previous query so results cannot land out of order. */
    private fun onSearchQuery(q: String) {
        searchJob?.cancel()
        if (q.length < 3) { searchResults = emptyList(); return }
        searchJob = lifecycleScope.launch {
            delay(250)
            val p = pub ?: return@launch
            val iter = p.search(q) ?: return@launch         // SearchIterator (experimental API)
            try {
                val out = mutableListOf<Locator>()
                while (out.size < 100) {
                    val page = iter.next().getOrNull() ?: break    // Try<LocatorCollection, SearchError>
                    out += page.locators
                }
                searchResults = out
            } finally { iter.close() }
        }
    }

    @Composable
    private fun Overlay() {
        val ui = readerUi(effectiveTheme(prefs))
        Box(Modifier.fillMaxSize()) {
            // R-06: warm-light filter is an app-local tint; it never touches system settings.
            if (prefs.warmth > 0f) Box(Modifier.fillMaxSize().background(CColor(0xFFFF9A2E).copy(alpha = prefs.warmth * 0.30f)))
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = K.mint)
            toast.Host(Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp))

            AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                Column(Modifier.fillMaxWidth().background(ui.chrome)) {
                    Row(Modifier.statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        KIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to library", { finish() }, tint = ui.chromeText)
                        Text(bookTitle, Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, textAlign = ComposeTextAlign.Center, color = ui.chromeText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        // Highlighting lives in the long-press selection menu; the top bar only carries
                        // the bookmark toggle, whose icon and label flip when this chapter has one.
                        val bookmarkHere = remember(marks, chapterHref) {
                            val h = chapterHref
                            h != null && marks.any { it.kind == "bookmark" && markHref(it) == h }
                        }
                        KIconButton(
                            if (bookmarkHere) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                            if (bookmarkHere) "Remove this chapter's bookmark" else "Bookmark this chapter",
                            { toggleChapterBookmark(bookmarkHere) },
                            tint = ui.chromeText,
                        )
                    }
                    HorizontalDivider(color = ui.line)
                }
            }

            jumpBack?.let { j ->   // R-08: "Back to where you were"
                // A short-lived offer: it clears itself after a delay, and any new jump re-arms it.
                LaunchedEffect(j) { delay(6000); jumpBack = null }
                Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp)).background(K.mint)
                    .clickable { goTo(j); jumpBack = null }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = K.mintInk, modifier = Modifier.size(20.dp)); Text("Back to where you were", color = K.mintInk, fontWeight = FontWeight.ExtraBold)
                }
            }

            footnote?.let { note ->   // R-08: footnote card, so you do not lose your page
                Column(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 150.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.surf2).padding(16.dp)) {
                    Text("Footnote", color = K.mint, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    Text(note, style = KType.small, modifier = Modifier.padding(bottom = 12.dp))
                    KButton("Close", { footnote = null }, style = KButtonStyle.Secondary, small = true)
                }
            }

            AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                Column(Modifier.fillMaxWidth().background(ui.chrome)) {
                    HorizontalDivider(color = ui.line)
                    Column(Modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
                        val ro = pub?.readingOrder.orEmpty()
                        var drag by remember { mutableStateOf<Float?>(null) }
                        var expanded by remember { mutableStateOf(false) }
                        // Preload the chapter under the slider while dragging so releasing feels instant,
                        // debounced so a fast scrub does not load every chapter it passes over.
                        // A plain holder, not snapshot state: a Job in mutableStateOf would recompose
                        // this scope on every scrub tick while dragging.
                        val preloadJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
                        fun previewSeek(v: Float) {
                            preloadJob[0]?.cancel()
                            preloadJob[0] = lifecycleScope.launch {
                                delay(220)
                                val l = positions.getOrNull((v * (positions.size - 1)).toInt()) ?: return@launch
                                nav?.go(l, animated = false)
                            }
                        }
                        // While scrubbing, the label names the chapter the slider points at.
                        val preview = drag?.let { v ->
                            val idx = (v * (positions.size - 1)).toInt()
                            val loc = positions.getOrNull(idx)
                            val title = loc?.let { chapterTitles[it.href.toString()] ?: chapterTitles[it.href.toString().substringAfterLast('/')] ?: it.title }
                            title?.takeIf { it.isNotBlank() }
                                ?: ro.getOrNull((v * (ro.size - 1)).toInt())?.title?.takeIf { it.isNotBlank() }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(preview ?: chapterTitle, color = if (preview != null) ui.accent else ui.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(timeLeft(bookProgress), color = ui.muted, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        }
                        KSlider(drag ?: bookProgress, { v -> drag = v; previewSeek(v) }, accent = ui.accent, trackColor = ui.line, onFinished = {   // R-07: seek by position
                            drag?.let { v ->
                                val l = positions.getOrNull((v * (positions.size - 1)).toInt())
                                if (l != null) goTo(l)
                                else pub?.let { p -> ro.getOrNull((v * (ro.size - 1)).toInt())?.let { lk -> p.locatorFromLink(lk)?.let(::goTo) } }
                            }
                            drag = null
                        })
                        // The bar stays minimal; the tools live behind "Options".
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = ui.chromeText, modifier = Modifier.size(20.dp))
                            Text(if (expanded) "Less" else "Options", color = ui.chromeText, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                        AnimatedVisibility(expanded) {
                            Row(Modifier.fillMaxWidth()) {
                                ReaderTool(Icons.AutoMirrored.Filled.List, "Contents", ui) { sheet = Sheet.CONTENTS }
                                ReaderTool(Icons.Default.Search, "Search", ui) { sheet = Sheet.SEARCH }
                                ReaderTool(Icons.Default.StickyNote2, "Notes", ui) { sheet = Sheet.NOTES }
                                ReaderTool(Icons.Default.TextFormat, "Display", ui) { sheet = Sheet.DISPLAY }
                            }
                        }
                    }
                }
            }

            if (error != null) KSheet({ finish() }, "This book can't be opened") {
                Text("The file may be damaged or too large. The rest of your data is safe.", style = KType.body.copy(color = K.mut))
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton("Keep it", { finish() })
                    KButton("Remove from library", { app.store.remove(bookId); finish() }, style = KButtonStyle.Danger)
                }
            }
            if (sheet != Sheet.NONE) KSheet({ sheet = Sheet.NONE }, when (sheet) { Sheet.DISPLAY -> "Display"; Sheet.CONTENTS -> "Contents"; Sheet.SEARCH -> "Search in book"; else -> "Notes and highlights" }) {
                when (sheet) {
                    Sheet.DISPLAY -> DisplaySheet(prefs, ::updatePrefs, showColumns = isTablet)
                    Sheet.CONTENTS -> ContentsSheet(pub, ::goTo)
                    Sheet.SEARCH -> SearchSheet(searchResults, ::onSearchQuery, ::goTo)
                    Sheet.NOTES -> AnnotationsContent(bookTitle, marks,
                        onGo = { m -> runCatching { Locator.fromJSON(JSONObject(m.locator)) }.getOrNull()?.let(::goTo) },
                        onDelete = { m -> app.store.update(bookId) { b -> b.copy(marks = b.marks.filterNot { it.id == m.id }) } },
                        onExport = { exportNotes.launch("${bookTitle.take(40)}-notes.md") })   // A-03
                    Sheet.NONE -> {}
                }
            }
            BackHandler(sheet != Sheet.NONE) { sheet = Sheet.NONE }
        }
    }
}
