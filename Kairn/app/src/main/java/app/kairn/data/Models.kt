package app.kairn.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class Status { NONE, WANT, READING, FINISHED }
enum class ThemeId { LIGHT, DARK, SEPIA, BLACK, GRAY }   // appended, so stored names keep parsing

/** Ambient wash behind the glass panels. PLAIN turns it off. */
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
         * Null when the mark is unusable (no id, or no locator to jump to): a bad mark must never take
         * its whole book down with it - see [Book.from].
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
    val pageColor: Int? = null,                // a custom page colour laid over the theme
    val inkColor: Int? = null,                 // and the ink that goes with it
    val fontFamily: String? = null,            // null = the publication's own typeface
    val scrollSpeed: Float = 44f,              // dp per second for auto scroll
    val speechRate: Float = 1f,                // read aloud, 1 = the engine's own pace
) {
    fun toJson() = JSONObject().put("theme", theme.name).put("fontScale", fontScale)
        .put("lineHeight", lineHeight).put("paragraphSpacing", paragraphSpacing)
        .put("margins", margins).put("justify", justify).put("hyphens", hyphens).put("twoPage", twoPage)
        .put("scroll", scroll).put("warmth", warmth.toDouble()).put("brightness", brightness.toDouble())
        .put("pageColor", pageColor ?: JSONObject.NULL).put("inkColor", inkColor ?: JSONObject.NULL)
        .put("fontFamily", fontFamily ?: JSONObject.NULL).put("scrollSpeed", scrollSpeed.toDouble())
        .put("speechRate", speechRate.toDouble())

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
                pageColor = o.optInt("pageColor", 0).takeIf { it != 0 } ?: base.pageColor,
                inkColor = o.optInt("inkColor", 0).takeIf { it != 0 } ?: base.inkColor,
                scrollSpeed = o.optDouble("scrollSpeed", base.scrollSpeed.toDouble()).toFloat(),
                speechRate = o.optDouble("speechRate", base.speechRate.toDouble()).toFloat(),
                fontFamily = if (o.has("fontFamily") && !o.isNull("fontFamily"))
                    o.optString("fontFamily").ifEmpty { null } else base.fontFamily,
            )
        }
    }
}

/**
 * One book = one JSON file (DEC-8, and the shape X-20 sync needs: one file per book limits conflicts).
 * @Immutable is a real performance promise here, not decoration: `marks` holds `List<Mark>`, which Compose treats as UNSTABLE, so without this every book card would recompose whenever its parent did.
 * All fields are `val` and are rebuilt via copy(), never mutated in place, so the promise holds.
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
    /** The book's word count, measured once. A property of the file, and ids are content hashes, so it cannot go stale. */
    val words: Int = 0,
    val marks: List<Mark> = emptyList(),
    val prefs: ReaderPrefs? = null,  // R-10
) {
    fun toJson() = JSONObject().put("v", 1).put("id", id).put("title", title).put("author", author)
        .put("uri", uri).put("folder", folder ?: JSONObject.NULL).put("status", status.name)
        .put("tags", JSONArray(tags)).put("collections", JSONArray(collections))
        .put("missing", missing).put("broken", broken).put("locator", locator ?: JSONObject.NULL)
        .put("progress", progress.toDouble()).put("added", added).put("lastRead", lastRead).put("words", words)
        .put("marks", JSONArray(marks.map { it.toJson() }))
        .put("prefs", prefs?.toJson() ?: JSONObject.NULL)

    companion object {
        private fun strings(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getString(it) }

        /**
         * Null only when the record is genuinely unusable (no id, or no uri). Every other field is read
         * leniently and a single malformed mark is skipped rather than failing the book.
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
            lastRead = o.optLong("lastRead"), words = o.optInt("words"),
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
    val glass: Boolean = true,                // glass surfaces (default); false = solid
    val ambient: Ambient = Ambient.MINT,      // ambient wash colour
    val autoSwitch: Boolean = false,          // R-05: follow system dark/light
    val keepScreenOn: Boolean = true,         // R-11
    val fullscreen: Boolean = false,          // R-11
    val volumeKeys: Boolean = true,           // R-07
    val swipeChapters: Boolean = true,        // R-07: swipe left/right to change chapter in scroll mode
    val swipeThreshold: Int = 40,             // R-07: how far (dp) a horizontal drag must go to count
    val autoDetect: Boolean = true,           // L-01: find EPUBs already on the device on open
    val folders: List<WatchedFolder> = emptyList(),
    val defaults: ReaderPrefs = ReaderPrefs(),
    val onboarded: Boolean = false,           // N-06
    val shelves: List<String> = emptyList(),  // collections created before any book is in them
    val extraTags: List<String> = emptyList(),
    val lastBackup: Long = 0,                 // DS-01: shown on the Backup screen
    val lastNudge: Long = 0,                  // when the "you have not backed up" card was dismissed
    val readingWpm: Int = 240,               // the reader's own pace, for "min left"
    val lastBackupName: String = "",
    val checkUpdates: Boolean = true,       // DS-04: one request a day, off in Settings; see data/Update
    val lastUpdateCheck: Long = 0,          // when the last check ran, so it is at most once a day
    val shareSelection: Boolean = true,     // the Share item in the text-selection menu
    val lookupSelection: Boolean = true,    // the Look up item in the text-selection menu
    val autoBackupFolder: String = "",      // SAF tree for automatic backups; empty = off
    val autoBackupDays: Int = 7,            // how often a backup is written while the app is open
    val autoBackupKeep: Int = 3,            // how many of ours to keep in that folder
    val lastAutoBackup: Long = 0,           // when the last automatic one was written
    val lastSweep: Long = 0,                // the last full device sweep; see Library.scan
) {
    fun toJson() = JSONObject().put("theme", theme.name).put("glass", glass).put("ambient", ambient.name).put("autoSwitch", autoSwitch)
        .put("onboarded", onboarded).put("shelves", JSONArray(shelves)).put("extraTags", JSONArray(extraTags))
        .put("lastBackup", lastBackup).put("lastBackupName", lastBackupName).put("lastNudge", lastNudge).put("readingWpm", readingWpm)
        .put("keepScreenOn", keepScreenOn).put("fullscreen", fullscreen).put("volumeKeys", volumeKeys)
        .put("swipeChapters", swipeChapters).put("swipeThreshold", swipeThreshold)
        .put("autoDetect", autoDetect)
        .put("checkUpdates", checkUpdates).put("lastUpdateCheck", lastUpdateCheck)
        .put("shareSelection", shareSelection).put("lookupSelection", lookupSelection)
        .put("autoBackupFolder", autoBackupFolder).put("autoBackupDays", autoBackupDays)
        .put("autoBackupKeep", autoBackupKeep).put("lastAutoBackup", lastAutoBackup).put("lastSweep", lastSweep)
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
                shareSelection = o.optBoolean("shareSelection", d.shareSelection),
                lookupSelection = o.optBoolean("lookupSelection", d.lookupSelection),
                autoBackupFolder = o.optString("autoBackupFolder", d.autoBackupFolder),
                autoBackupDays = o.optInt("autoBackupDays", d.autoBackupDays).coerceIn(1, 90),
                autoBackupKeep = o.optInt("autoBackupKeep", d.autoBackupKeep).coerceIn(1, 20),
                lastAutoBackup = o.optLong("lastAutoBackup", d.lastAutoBackup),
                lastSweep = o.optLong("lastSweep", d.lastSweep),
                // mapNotNull + optString: an entry with no usable uri is skipped, not a reason to discard
                // every other setting.
                folders = o.optJSONArray("folders")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val f = a.optJSONObject(i) ?: return@mapNotNull null
                        val uri = f.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        WatchedFolder(uri, f.optString("name"), f.optBoolean("enabled", true))
                    }
                } ?: emptyList(),
                defaults = ReaderPrefs.from(o.optJSONObject("defaults")),
                onboarded = o.optBoolean("onboarded", false),
                // optString on a JSON null yields the string "null", and getString throws: skip both.
                shelves = o.optJSONArray("shelves")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList(),
                extraTags = o.optJSONArray("extraTags")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList(),
                lastBackup = o.optLong("lastBackup", 0),
                lastNudge = o.optLong("lastNudge", 0),
                readingWpm = o.optInt("readingWpm", 240),
                lastBackupName = o.optString("lastBackupName"),
                checkUpdates = o.optBoolean("checkUpdates", d.checkUpdates),
                lastUpdateCheck = o.optLong("lastUpdateCheck", 0),
            )
        }
    }
}
