package app.kairn.ui

import android.content.Context
import app.kairn.R
import app.kairn.data.Book
import app.kairn.data.PositionsCache
import app.kairn.data.ReadiumHelper
import app.kairn.ui.theme.K
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.services.positions

/**
 * The open sequence, minus the parts that are lifecycle: parse the publication, measure it, name its
 * chapters, and build the navigator factory the Activity installs. Nothing here touches the reader's UI
 * state - the Activity publishes what comes back.
 */
internal class ReaderSession(
    private val ctx: Context,
    private val readium: ReadiumHelper,
    private val scope: CoroutineScope,
    private val positionsCache: PositionsCache,
) {

    class Prepared(
        val publication: Publication,
        val rtl: Boolean,
        val chapterTitles: Map<String, String>,
        val factory: EpubNavigatorFactory,
        val configuration: EpubNavigatorFragment.Configuration,
    )

    /**
     * Null means the file could not be opened (N-04): the caller owns the message.
     *
     * [onPositions] is handed the positions, how long they took, and whether they came from the cache -
     * measuring is the slow half of opening, so it must never hold up the parse. `true` means the store was
     * hit and nothing was measured.
     */
    suspend fun prepare(
        book: Book,
        highlightCallback: HighlightActionModeCallback,
        onPositions: suspend (Publication, List<Locator>, Long, Boolean) -> Unit,
    ): Prepared? {
        // Readium's AssetRetriever/PublicationOpener reference no dispatcher, so parsing must hop to IO.
        val p = withContext(Dispatchers.IO) { readium.open(book.uri.toUri()) } ?: return null

        val cached = positionsCache.load(book)
        if (cached != null) {
            onPositions(p, cached, 0L, true)
        } else {
            val tp = android.os.SystemClock.elapsedRealtime()
            // positions() scans the whole publication: the slow part of opening, so IO, never the main thread.
            // This rides the reader's own scope on purpose - leaving the book before it finishes cancels the
            // measurement, so a book that was only glanced at is never measured or cached in the background.
            scope.launch {
                val computed = withContext(Dispatchers.IO) { runCatching { p.positions() }.getOrDefault(emptyList()) }
                positionsCache.save(book, computed)
                onPositions(p, computed, android.os.SystemClock.elapsedRealtime() - tp, false)
            }
        }

        return Prepared(
            publication = p,
            rtl = p.metadata.readingProgression == ReadingProgression.RTL,
            chapterTitles = ReaderLogic.chapterTitles(
                p.tableOfContents.map { it.toTocNode() }, p.readingOrder.map { it.toTocNode() },
            ),
            factory = EpubNavigatorFactory(p),
            configuration = configuration(highlightCallback),
        )
    }

    private fun configuration(highlightCallback: HighlightActionModeCallback) =
        EpubNavigatorFragment.Configuration(
            selectionActionModeCallback = highlightCallback,
            servedAssets = listOf("fonts/.*"),      // the app's own typefaces, served to the webview
        ).apply {
            // Literata and Manrope are variable: one file spans the weight axis, so the range is the face.
            addFontFamilyDeclaration(FontFamily("Literata"), listOf(FontFamily("serif"))) {
                addFontFace {
                    addSource("fonts/literata.ttf", preload = true)
                    setFontWeight(400..800)
                }
            }
            addFontFamilyDeclaration(FontFamily("Manrope"), listOf(FontFamily("sans-serif"))) {
                addFontFace {
                    addSource("fonts/manrope.ttf", preload = true)
                    setFontWeight(300..800)
                }
            }
            // Libron is static: four files, one per style/weight pair.
            addFontFamilyDeclaration(FontFamily("Libron"), listOf(FontFamily("serif"))) {
                addFontFace { addSource("fonts/Libron-Regular.ttf", preload = true); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.NORMAL) }
                addFontFace { addSource("fonts/Libron-Bold.ttf"); setFontStyle(FontStyle.NORMAL); setFontWeight(FontWeight.BOLD) }
                addFontFace { addSource("fonts/Libron-Italic.ttf"); setFontStyle(FontStyle.ITALIC); setFontWeight(FontWeight.NORMAL) }
                addFontFace { addSource("fonts/Libron-BoldItalic.ttf"); setFontStyle(FontStyle.ITALIC); setFontWeight(FontWeight.BOLD) }
            }
        }

    /** The palette labels the system selection menu shows, in Tokens order. */
    fun colourLabels(): List<Pair<String, Int>> =
        K.highlightColors.zip(
            listOf(
                R.string.s_colour_amber, R.string.s_colour_mint, R.string.s_colour_rose,
                R.string.s_colour_blue, R.string.s_colour_violet, R.string.s_colour_sand,
            ),
        ).map { (pair, name) -> ctx.getString(name) to pair.second.toArgb() }
}

/** Readium's Link carries an Href that only Android can parse, so flatten it before the pure walk. */
internal fun Link.toTocNode(): ReaderLogic.TocNode =
    ReaderLogic.TocNode(href.toString(), title, children.map { it.toTocNode() })
