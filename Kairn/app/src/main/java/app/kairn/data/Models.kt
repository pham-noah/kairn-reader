package app.kairn.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class Status { NONE, WANT, READING, FINISHED }
enum class ThemeId { LIGHT, DARK, SEPIA, BLACK }

/** Round 5 ambient wash behind the glass panels. PLAIN turns it off. */
enum class Ambient { MINT, OCEAN, SUNSET, PLAIN }

/** A highlight, note or bookmark (A-01). [locator] is Readium Locator JSON. */
@Immutable
data class Mark(
    val id: String,
    val kind: String,          // "highlight" | "note" | "bookmark"
    val color: Int,            // ARGB
    val quote: String,
    val note: String,
    val locator: String,
    val created: Long,
) {
    fun toJson() = JSONObject().put("id", id).put("kind", kind).put("color", color)
        .put("quote", quote).put("note", note).put("locator", locator).put("created", created)

    companion object {
        /**
         * Null when this mark is unusable (no id, or no locator to jump to). A mark that cannot be read
         * must never take its whole book down with it - see [Book.from].
         */
        fun from(o: JSONObject): Mark? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val locator = o.optString("locator").takeIf { it.isNotBlank() } ?: return null
            return Mark(
                id, o.optString("kind", "highlight"), o.optInt("color"), o.optString("quote"),
                o.optString("note"), locator, o.optLong("created"),
            )
        }
    }
}

/** Per-book reading settings (R-04, R-10). Null on a book means "use the global defaults". */
@Immutable
data class ReaderPrefs(
    val theme: ThemeId = ThemeId.DARK,         // DEC-4
    val fontScale: Double = 1.0,
    val lineHeight: Double = 1.5,
    val paragraphSpacing: Double = 0.5,
    val margins: Double = 1.0,
    val justify: Boolean = false,
    val hyphens: Boolean = true,
    val twoPage: Boolean = false,              // R-14: two-page spread on a wide screen
    val scroll: Boolean = false,               // R-03
    val warmth: Float = 0f,                    // R-06, 0..1, app-local overlay
    val brightness: Float = -1f,               // R-06, -1 = follow system
) {
    fun toJson() = JSONObject().put("theme", theme.name).put("fontScale", fontScale)
        .put("lineHeight", lineHeight).put("paragraphSpacing", paragraphSpacing)
        .put("margins", margins).put("justify", justify).put("hyphens", hyphens).put("twoPage", twoPage)
        .put("scroll", scroll).put("warmth", warmth.toDouble()).put("brightness", brightness.toDouble())

    companion object {
        fun from(o: JSONObject?, base: ReaderPrefs = ReaderPrefs()): ReaderPrefs {
            if (o == null) return base
            return ReaderPrefs(
                theme = runCatching { ThemeId.valueOf(o.getString("theme")) }.getOrDefault(base.theme),
                fontScale = o.optDouble("fontScale", base.fontScale),
                lineHeight = o.optDouble("lineHeight", base.lineHeight),
                paragraphSpacing = o.optDouble("paragraphSpacing", base.paragraphSpacing),
                margins = o.optDouble("margins", base.margins),
                justify = o.optBoolean("justify", base.justify),
                hyphens = o.optBoolean("hyphens", base.hyphens),
                twoPage = o.optBoolean("twoPage", base.twoPage),
                scroll = o.optBoolean("scroll", base.scroll),
                warmth = o.optDouble("warmth", base.warmth.toDouble()).toFloat(),
                brightness = o.optDouble("brightness", base.brightness.toDouble()).toFloat(),
            )
        }
    }
}

/**
 * One book = one JSON file (DEC-8, and the shape X-20 sync needs: one file per book limits conflicts).
 *
 * @Immutable is a real performance promise here, not decoration: `mark` holds `List<Mark>`, and
 * Compose treats `List` as UNSTABLE, so without this a `Book` parameter made every book card
 * unskippable - every visible item recomposed whenever the list's parent recomposed. All fields are
 * `val` and are rebuilt via copy(), never mutated in place, so the promise holds.
 */
@Immutable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val uri: String,                 // SAF tree/document URI, or file:// for copied "open with" books
    val folder: String? = null,      // watched-folder URI it came from, if any
    val status: Status = Status.NONE,
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val missing: Boolean = false,    // L-01: never auto-delete, so position + notes survive
    val broken: Boolean = false,     // N-04: failed to open
    val locator: String? = null,     // R-09
    val progress: Float = 0f,
    val added: Long = System.currentTimeMillis(),
    val lastRead: Long = 0,
    val marks: List<Mark> = emptyList(),
    val prefs: ReaderPrefs? = null,  // R-10
) {
    fun toJson() = JSONObject().put("v", 1).put("id", id).put("title", title).put("author", author)
        .put("uri", uri).put("folder", folder ?: JSONObject.NULL).put("status", status.name)
        .put("tags", JSONArray(tags)).put("collections", JSONArray(collections))
        .put("missing", missing).put("broken", broken).put("locator", locator ?: JSONObject.NULL)
        .put("progress", progress.toDouble()).put("added", added).put("lastRead", lastRead)
        .put("marks", JSONArray(marks.map { it.toJson() }))
        .put("prefs", prefs?.toJson() ?: JSONObject.NULL)

    companion object {
        private fun strings(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getString(it) }

        /**
         * Null only when the record is genuinely unusable (no id, or no uri). Every other field is read
         * leniently and a single malformed mark is skipped rather than failing the book: before this, a
         * throwing `getString` meant one bad field dropped a book - and all its marks - out of the
         * library while the file stayed on disk, invisibly.
         */
        fun from(o: JSONObject): Book? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return null
            return Book(
            id = id, title = o.optString("title").ifBlank { "Untitled" }, author = o.optString("author"),
            uri = uri, folder = o.optString("folder").takeIf { it.isNotEmpty() && it != "null" },
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.NONE),
            tags = strings(o.optJSONArray("tags")), collections = strings(o.optJSONArray("collections")),
            missing = o.optBoolean("missing"), broken = o.optBoolean("broken"),
            locator = o.optString("locator").takeIf { it.isNotEmpty() && it != "null" },
            progress = o.optDouble("progress", 0.0).toFloat(), added = o.optLong("added"),
            lastRead = o.optLong("lastRead"),
            marks = o.optJSONArray("marks")?.let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { Mark.from(a.getJSONObject(i)) }.getOrNull() }
            } ?: emptyList(),
            prefs = o.optJSONObject("prefs")?.let { ReaderPrefs.from(it) },
        )
        }
    }
}

data class WatchedFolder(val uri: String, val name: String, val enabled: Boolean = true)

data class AppSettings(
    val theme: ThemeId = ThemeId.DARK,        // DEC-4
    val glass: Boolean = true,                // Round 5 glass surfaces (default); false = round 4 Solid
    val ambient: Ambient = Ambient.MINT,      // Round 5 ambient wash colour
    val autoSwitch: Boolean = false,          // R-05: follow system dark/light
    val keepScreenOn: Boolean = true,         // R-11
    val fullscreen: Boolean = false,          // R-11
    val volumeKeys: Boolean = true,           // R-07
    val swipeChapters: Boolean = true,        // R-07: swipe left/right to change chapter in scroll mode
    val swipeThreshold: Int = 40,             // R-07: how far (dp) a horizontal drag must go to count
    val autoDetect: Boolean = true,           // L-01: find EPUBs already on the device on open (like Lithium)
    val folders: List<WatchedFolder> = emptyList(),
    val defaults: ReaderPrefs = ReaderPrefs(),
    val onboarded: Boolean = false,           // N-06
    val shelves: List<String> = emptyList(),  // collections created before any book is in them
    val extraTags: List<String> = emptyList(),
    val lastBackup: Long = 0,                 // DS-01: shown on the Backup screen
    val lastBackupName: String = "",
) {
    fun toJson() = JSONObject().put("theme", theme.name).put("glass", glass).put("ambient", ambient.name).put("autoSwitch", autoSwitch)
        .put("onboarded", onboarded).put("shelves", JSONArray(shelves)).put("extraTags", JSONArray(extraTags))
        .put("lastBackup", lastBackup).put("lastBackupName", lastBackupName)
        .put("keepScreenOn", keepScreenOn).put("fullscreen", fullscreen).put("volumeKeys", volumeKeys)
        .put("swipeChapters", swipeChapters).put("swipeThreshold", swipeThreshold)
        .put("autoDetect", autoDetect)
        .put("folders", JSONArray(folders.map {
            JSONObject().put("uri", it.uri).put("name", it.name).put("enabled", it.enabled)
        }))
        .put("defaults", defaults.toJson())

    companion object {
        fun from(o: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                theme = runCatching { ThemeId.valueOf(o.getString("theme")) }.getOrDefault(d.theme),
                glass = o.optBoolean("glass", d.glass),
                ambient = runCatching { Ambient.valueOf(o.getString("ambient")) }.getOrDefault(d.ambient),
                autoSwitch = o.optBoolean("autoSwitch", d.autoSwitch),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                fullscreen = o.optBoolean("fullscreen", d.fullscreen),
                volumeKeys = o.optBoolean("volumeKeys", d.volumeKeys),
                swipeChapters = o.optBoolean("swipeChapters", d.swipeChapters),
                swipeThreshold = o.optInt("swipeThreshold", d.swipeThreshold),
                autoDetect = o.optBoolean("autoDetect", d.autoDetect),
                // mapNotNull + optString: one folder entry without a uri used to throw, and the throw took
                // the WHOLE settings file down with it. A blank/absent uri is an entry we cannot use, not a
                // reason to discard every other setting.
                folders = o.optJSONArray("folders")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val f = a.optJSONObject(i) ?: return@mapNotNull null
                        val uri = f.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        WatchedFolder(uri, f.optString("name"), f.optBoolean("enabled", true))
                    }
                } ?: emptyList(),
                defaults = ReaderPrefs.from(o.optJSONObject("defaults")),
                onboarded = o.optBoolean("onboarded", false),
                shelves = o.optJSONArray("shelves")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                extraTags = o.optJSONArray("extraTags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                lastBackup = o.optLong("lastBackup", 0),
                lastBackupName = o.optString("lastBackupName"),
            )
        }
    }
}
