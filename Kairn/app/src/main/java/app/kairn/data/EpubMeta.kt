package app.kairn.data

import androidx.compose.runtime.Immutable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The handful of OPF fields the library and the book's detail sheet show, and that Readium's own
 * `Metadata` does not surface: a series (Calibre writes it as a private `meta`; EPUB 3 writes it as a
 * collection with `collection-type="series"`), a description, a publisher, a date and a language.
 *
 * Read from the zip rather than through Readium on purpose: `Publication.metadata` exposes authors, subjects
 * and `belongsTo`, but not `calibre:series`, and a detail sheet wants the description too. Two pass-throughs
 * over a few-kB entry at import time, not per open.
 */
@Immutable
data class EpubMeta(
    val series: String? = null,
    val seriesIndex: Double? = null,
    val description: String? = null,
    val publisher: String? = null,
    val published: String? = null,
    val language: String? = null,
) {
    /** Nothing worth a second read: a record that got this from an EPUB does not need read again. */
    val isEmpty: Boolean get() = series == null && description == null && publisher == null &&
        published == null && language == null

    companion object {
        /** "Discworld 3" for a numbered book, the plain name otherwise. */
        fun seriesLabel(series: String?, index: Double? = null): String? {
            val name = series?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val i = index ?: return name
            if (i <= 0.0) return name
            val n = if (i == Math.floor(i) && !i.isInfinite()) i.toLong().toString() else i.toString()
            return "$name $n"
        }
    }
}

/**
 * Reads [EpubMeta] out of an EPUB. [open] gives a FRESH stream over the same file each call, because a zip
 * entry is only reachable by reading past the ones before it: `META-INF/container.xml` names the OPF, and the
 * OPF then has to be read from the start again.
 */
object EpubOpf {

    /** An OPF is metadata; anything past this is a crafted file, not a book. */
    const val MAX_ENTRY = 2 * 1024 * 1024

    fun read(open: () -> InputStream?): EpubMeta? = runCatching {
        val root = open()?.use { rootFile(ZipInputStream(it)) } ?: return@runCatching null
        val xml = open()?.use { entry(ZipInputStream(it), root) }?.toString(Charsets.UTF_8)
            ?: return@runCatching null
        parse(xml)
    }.getOrNull()

    /** The manifest's own name for itself, from `META-INF/container.xml`. */
    private fun rootFile(zip: ZipInputStream): String? {
        val bytes = entry(zip, "META-INF/container.xml") ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        // Attribute order and quoting both vary; this is the only value needed from that file.
        return Regex("full-path\\s*=\\s*(\"([^\"]+)\"|'([^']+)')")
            .find(text)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Reads one entry's bytes, or null when it is absent or past [MAX_ENTRY]. */
    private fun entry(zip: ZipInputStream, name: String): ByteArray? {
        val want = name.removePrefix("./")
        while (true) {
            val e = zip.nextEntry ?: return null
            // Normalise, so "OEBPS/./content.opf" and "OEBPS/content.opf" are the same entry.
            if (e.name.replace("//", "/") != want) continue
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = zip.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ENTRY) return null
            }
            return out.toByteArray()
        }
    }

    /**
     * The OPF itself. Exposed for tests: it takes text, touches no zip and no Android API.
     *
     * A DOM parse with doctypes and external entities refused - an OPF comes from a file the user may have
     * downloaded, and the only thing worse than a missing series is a parser that resolves an entity.
     */
    fun parse(xml: String): EpubMeta? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }.getOrNull()
            runCatching { isExpandEntityReferences = false }.getOrNull()
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.getOrNull()
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }.getOrNull()
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }.getOrNull()
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        // A well-formed document is not an OPF: an HTML page or an empty string parses cleanly and would
        // otherwise come back as "no metadata", which is a different answer from "this is not a book".
        val root = doc.documentElement ?: return@runCatching null
        if (local(root) != "package") return@runCatching null
        val all = doc.getElementsByTagName("*")

        fun texts(name: String): List<String> = (0 until all.length).mapNotNull { i ->
            val el = all.item(i) as? Element ?: return@mapNotNull null
            if (local(el) != name) return@mapNotNull null
            el.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Calibre's private metas: <meta name="calibre:series" content="Discworld"/>
        fun metaName(name: String): String? = (0 until all.length).firstNotNullOfOrNull { i ->
            val el = all.item(i) as? Element ?: return@firstNotNullOfOrNull null
            if (local(el) != "meta") return@firstNotNullOfOrNull null
            if (!el.getAttribute("name").equals(name, ignoreCase = true)) return@firstNotNullOfOrNull null
            el.getAttribute("content").trim().takeIf { it.isNotEmpty() }
        }

        // EPUB 3: <meta property="belongs-to-collection" id="c1">Discworld</meta> with refines for its type
        // and position. Only a collection that SAYS it is a series is taken as one.
        // One pass to index the refines, then one to read the collections. The nested scan was O(n^2) on a
        // crafted OPF - thousands of collections, each walked against thousands of metas - and this runs at
        // import, on the main path of opening a book.
        fun collections(): List<Triple<String, String?, String?>> {
            val refined = HashMap<String, MutableMap<String, String>>()
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                if (local(el) != "meta") continue
                val ref = el.getAttribute("refines")
                if (!ref.startsWith("#")) continue
                val props = refined.getOrPut(ref.removePrefix("#")) { HashMap() }
                props[el.getAttribute("property").lowercase()] = el.textContent?.trim().orEmpty()
            }
            val out = ArrayList<Triple<String, String?, String?>>()
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                if (local(el) != "meta") continue
                if (!el.getAttribute("property").equals("belongs-to-collection", ignoreCase = true)) continue
                val name = el.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val props = refined[el.getAttribute("id")].orEmpty()
                out += Triple(
                    name,
                    props["collection-type"]?.takeIf { it.isNotEmpty() },
                    props["group-position"]?.takeIf { it.isNotEmpty() },
                )
            }
            return out
        }

        val calibre = metaName("calibre:series")
        val series3 = collections().firstOrNull { it.second.equals("series", ignoreCase = true) }
        val series = calibre ?: series3?.first
        val indexText = metaName("calibre:series_index") ?: series3?.third
        val index = indexText?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

        // Every field is clipped: an OPF is a file, and a "series" of 900 kB is not a series. The detail
        // sheet draws these, the library sorts by them, and both are bounded by what a row can show.
        EpubMeta(
            series = series?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
            seriesIndex = index,
            description = texts("description").firstOrNull()?.let(::plainText)?.takeIf { it.isNotEmpty() },
            publisher = texts("publisher").firstOrNull()?.take(200),
            published = texts("date").firstOrNull()?.take(64),
            language = texts("language").firstOrNull()?.take(32),
        )
    }.getOrNull()

    private fun local(el: Element): String =
        (el.localName ?: el.tagName.substringAfter(':')).lowercase()

    /**
     * A description is usually a fragment of HTML. The detail sheet draws text, not markup, so tags go and
     * entities stay as the parser left them. Bounded: a "description" can be a whole novella.
     */
    fun plainText(raw: String, limit: Int = 4000): String {
        val noTags = raw.replace(Regex("<[^>]{0,2000}>"), " ")
        val unescaped = noTags
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        return unescaped.replace(Regex("\\s+"), " ").trim().take(limit)
    }
}
