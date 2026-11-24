package app.kairn.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import app.kairn.ui.theme.coverPalettes
import kotlin.math.abs
import kotlin.math.max

/**
 * The accent a book's own cover suggests.
 *
 * "Match the cover" is only worth having if it stays legible: a cover is a picture, and the honest colour of a
 * picture is usually too dark, too grey or too pale to sit under a switch and carry text. So the cover supplies the
 * *hue* and nothing else - [normalise] pins saturation and lightness into the band the five hand-picked accents
 * occupy, and [inkFor] derives the ink from the result exactly as the fixed palette does by hand.
 *
 * Sampling reads the thumbnail that the shelf already decoded and cached, so this costs one small decode per book
 * per process. Books with no artwork borrow the colour their generated cover is drawn in, which is a real colour of
 * the book as the reader sees it, so those keep working without a special case at the call site.
 */
object CoverAccent {

    private val cache = LruCache<String, Int>(64)

    /** Cache key carries the file's mtime: a cover replaced by a restore must not keep the old accent. */
    private fun key(book: Book, file: java.io.File) = "${book.id}:${file.lastModified()}:${file.length()}"

    /**
     * The accent for one book, as ARGB. Never throws and never returns 0: a cover that cannot be read falls back to
     * the generated-cover palette for that title, which is what the shelf is drawing anyway.
     */
    fun of(ctx: Context, store: Store, book: Book): Int {
        val file = listOf(store.thumbFile(book.id), store.coverFile(book.id)).firstOrNull { it.exists() }
            ?: return fallback(book)
        val k = key(book, file)
        cache.get(k)?.let { return it }
        val sampled = runCatching { sample(file) }.getOrNull()
        val argb = if (sampled != null) normalise(sampled) else fallback(book)
        cache.put(k, argb)
        return argb
    }

    /** The generated cover's own first colour: the shelf prints this book in it. */
    fun fallback(book: Book): Int {
        val (c1, _) = coverPalettes[(book.title.hashCode().and(0x7fffffff)) % coverPalettes.size]
        return normalise(c1.toArgb())
    }

    /**
     * The most usable colour in a cover: saturated enough to read as a colour at 44dp, and not so dark that the
     * app's dark chrome swallows it. Scored on saturation, weighted towards the middle of the cover where the art
     * is, rather than the border every cover has.
     */
    internal fun sample(file: java.io.File): Int? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return null
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 32)
        val hsv = FloatArray(3)
        var best = -1f
        var bestColor = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                android.graphics.Color.colorToHSV(c, hsv)
                val s = hsv[1]
                val v = hsv[2]
                // Greys and near-blacks have a hue only by accident; a pale sky has no colour to give.
                if (s > 0.16f && v > 0.18f) {
                    val centre = 1f - (abs(x - w / 2f) / (w / 2f)) * 0.35f - (abs(y - h / 2f) / (h / 2f)) * 0.25f
                    val score = s * 0.62f + centre * 0.38f - (if (v > 0.92f) 0.25f else 0f)
                    if (score > best) {
                        best = score
                        bestColor = c
                    }
                }
                x += stepX
            }
            y += stepY
        }
        bmp.recycle()
        return if (best < 0f) null else bestColor
    }

    /**
     * Pins a cover colour into the band the hand-picked accents live in: hue untouched, saturation lifted to at
     * least [MIN_S] (a washed-out cover still has to read as a colour) and lightness held inside [MIN_L]..[MAX_L]
     * so both dark ink and dark chrome are safe against it.
     */
    internal fun normalise(argb: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        hsv[1] = hsv[1].coerceIn(MIN_S, MAX_S)
        hsv[2] = hsv[2].coerceIn(MIN_L, MAX_L)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /**
     * The ink that goes on [argb], by the same rule the fixed palette follows by hand: relative luminance decides,
     * and the threshold sits at 0.45 rather than 0.5 because the accents are all high-lightness and a mid-tone ink
     * stays readable on the darker end of the band.
     */
    internal fun inkFor(argb: Int): Int {
        val r = android.graphics.Color.red(argb) / 255f
        val g = android.graphics.Color.green(argb) / 255f
        val b = android.graphics.Color.blue(argb) / 255f
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (lum > 0.45f) 0xFF0B0E10.toInt() else 0xFFF2F5F7.toInt()
    }

    internal const val MIN_S = 0.42f
    internal const val MAX_S = 0.78f
    internal const val MIN_L = 0.62f
    internal const val MAX_L = 0.84f

    /** Kept for the tests: the band a normalised colour must land in, whichever cover it came from. */
    internal fun inBand(argb: Int): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        // HSVToColor rounds to 8 bits, so a clamped 0.42 comes back as 0.4177 on a dark colour: the tolerance is
        // quantisation, not slop.
        return hsv[1] >= MIN_S - 0.012f && hsv[1] <= MAX_S + 0.012f && hsv[2] >= MIN_L - 0.012f && hsv[2] <= MAX_L + 0.012f
    }
}
