package app.kairn.data

import androidx.compose.runtime.Immutable
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One thing an OPDS catalog offers: a book to download, or a folder to open. */
@Immutable
data class OpdsEntry(
    val title: String,
    val author: String? = null,
    val epub: String? = null,
    /** A navigation link: the folder this entry opens. */
    val sub: String? = null,
    val cover: String? = null,
    val summary: String? = null,
    /** A navigation entry (a folder, a shelf, a search) rather than an acquisition. */
    val navigation: Boolean = false,
    val updated: Long = 0,
)

@Immutable
data class OpdsFeed(
    val title: String? = null,
    val entries: List<OpdsEntry> = emptyList(),
    /** The catalog's own "next page", already resolved against the request. */
    val next: String? = null,
)

/**
 * OPDS 1.2 over Atom, which is what Calibre's content server serves and what every other catalog speaks.
 *
 * Parsed with the same hardened DOM the OPF uses: a catalog is a remote document the user pointed the app
 * at, and it gets no more trust than a downloaded EPUB. Relative hrefs are resolved against the page they
 * came from, because a Calibre server answers with relative links everywhere.
 */
object Opds {

    /** A catalog page is a page, not a library: past this the list stops growing and "More" stops offering. */
    const val MAX_ENTRIES = 500

    /** One title, one author, one summary. A feed can put a whole book in a field; a row cannot show it. */
    private const val MAX_TITLE = 300
    private const val MAX_AUTHOR = 200
    private const val MAX_FEED_TITLE = 200

    private val ACQUISITION = setOf("http://opds-spec.org/acquisition")
    private val SUBSECTION = setOf("subsection")

    fun parse(xml: String, baseUrl: String): OpdsFeed? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isExpandEntityReferences = false }.getOrNull()
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.getOrNull()
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }.getOrNull()
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }.getOrNull()
        }
        val doc = factory.newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
            .parse(xml.byteInputStream())
        val root = doc.documentElement ?: return@runCatching null
        if (local(root) != "feed") return@runCatching null

        fun children(el: Element, name: String): List<Element> =
            (0 until el.childNodes.length).mapNotNull { i ->
                val c = el.childNodes.item(i) as? Element ?: return@mapNotNull null
                if (local(c) == name) c else null
            }

        fun href(el: Element): String? = resolve(baseUrl, el.getAttribute("href").trim())

        val feedTitle = children(root, "title").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?.take(MAX_FEED_TITLE)
        val next = (0 until root.childNodes.length)
            .mapNotNull { i -> root.childNodes.item(i) as? Element }
            .filter { local(it) == "link" && it.getAttribute("rel") == "next" }
            .firstNotNullOfOrNull { href(it) }

        val entries = children(root, "entry").map { e ->
            val links = children(e, "link")
            fun linkFor(vararg rels: String, types: List<String> = emptyList()): Element? =
                links.firstOrNull { l ->
                    (l.getAttribute("rel") in rels) && (types.isEmpty() || types.any { l.getAttribute("type").contains(it) })
                }
            val epub = linkFor(*ACQUISITION.toTypedArray(), types = listOf("epub", "pdf", "application/octet-stream"))
                ?: linkFor(*ACQUISITION.toTypedArray())
            val sub = linkFor(*SUBSECTION.toTypedArray())
            // The thumbnail is asked for by name: in document order the full image usually comes first.
            val image = linkFor("http://opds-spec.org/image/thumbnail")
                ?: linkFor("http://opds-spec.org/image")
            OpdsEntry(
                title = children(e, "title").firstOrNull()?.textContent?.trim().orEmpty()
                    .ifEmpty { "Untitled" }.take(MAX_TITLE),
                author = children(e, "author").firstOrNull()?.let { a ->
                    children(a, "name").firstOrNull()?.textContent?.trim()
                }?.takeIf { it.isNotEmpty() }?.take(MAX_AUTHOR),
                epub = epub?.let(::href),
                sub = sub?.let(::href),
                cover = image?.let(::href),
                summary = (children(e, "summary").firstOrNull() ?: children(e, "content").firstOrNull())
                    ?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { EpubOpf.plainText(it, 400) },
                navigation = epub == null && sub != null,
                updated = children(e, "updated").firstOrNull()?.textContent?.trim()?.let { parseTime(it) } ?: 0,
            )
        }
        OpdsFeed(title = feedTitle, entries = entries, next = next)
    }.getOrNull()

    /**
     * A page appended to what is already on screen, without growing forever.
     *
     * A catalog whose "next" points back at itself answers the same twenty books on every request. Deduped by
     * what the reader can see (the title and the link), and capped, so a "More" button cannot be a memory leak.
     */
    fun appendPage(current: List<OpdsEntry>, page: OpdsFeed, max: Int = MAX_ENTRIES): OpdsFeed {
        val seen = current.mapTo(HashSet()) { it.title to (it.epub ?: it.sub) }
        val added = page.entries.filter { (it.title to (it.epub ?: it.sub)) !in seen }
        val merged = (current + added).take(max)
        return OpdsFeed(
            title = page.title,
            entries = merged,
            // No more pages once the list is full: the button would only re-ask for what is already shown.
            next = if (merged.size >= max || added.isEmpty()) null else page.next,
        )
    }

    private fun local(el: Element): String = (el.localName ?: el.tagName.substringAfter(':')).lowercase()

    /** A relative href against the page it appeared on; an absolute one kept as it is. */
    fun resolve(base: String, href: String): String? {
        // A space or a control character in a URL is not a URL: it is either a broken feed or an attempt to
        // smuggle a header into the request line.
        if (href.isBlank() || href.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        return runCatching { URI(base).resolve(href).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Atom's dates are RFC 3339; a catalog that writes something else gets 0 and is simply unsorted. */
    private fun parseTime(text: String): Long = runCatching {
        java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.Instant.parse(text).toEpochMilli()
    }.getOrDefault(0L)
}
