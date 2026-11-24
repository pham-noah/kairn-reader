package app.kairn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Accent
import app.kairn.data.BarItem
import app.kairn.data.BarPreset
import app.kairn.data.CoverAccent
import app.kairn.data.ReaderPrefs
import app.kairn.data.ReadingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The presets are only useful if they are reversible and if "match the cover" cannot produce an unreadable accent,
 * and both of those are arithmetic rather than appearance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
// NATIVE, not the legacy graphics: the sampler reads pixels, and under the legacy mode every pixel is 0.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetsTest {

    @Test fun `a reading preset reads back as itself and a nudged slider reads back as none`() {
        ReadingPreset.entries.forEach { preset ->
            assertEquals(preset, ReadingPreset.of(preset.appliedTo(ReaderPrefs())))
        }
        assertNull(ReadingPreset.of(ReadingPreset.LARGE.appliedTo(ReaderPrefs()).copy(fontScale = 1.55)))
    }

    @Test fun `applying a preset leaves the rest of the prefs alone`() {
        val before = ReaderPrefs(theme = app.kairn.data.ThemeId.SEPIA, warmth = 0.4f, twoPage = true, fontFamily = "Libron")
        val after = ReadingPreset.COMPACT.appliedTo(before)
        assertEquals(before.theme, after.theme)
        assertEquals(before.warmth, after.warmth, 0f)
        assertTrue(before.twoPage == after.twoPage && before.fontFamily == after.fontFamily)
        assertEquals(ReadingPreset.COMPACT.fontScale, after.fontScale, 0.0)
    }

    @Test fun `each bar preset round-trips and a hand arrangement belongs to none`() {
        BarPreset.entries.forEach { preset ->
            assertEquals(preset, BarPreset.of(preset.order))
            // All six items, in some order: a preset that dropped one would hide a tool the reader never hid.
            assertEquals(BarItem.entries.size, preset.order.size)
            assertEquals(BarItem.entries.toSet(), preset.order.toSet())
        }
        assertEquals(BarPreset.STANDARD, BarPreset.of(BarItem.DEFAULT_ORDER))
        assertNull(BarPreset.of(BarItem.entries.reversed()))
    }

    @Test fun `a cover colour is pinned into the band the hand-picked accents occupy`() {
        // A near-black, a grey, a washed-out pastel and a fluorescent green: all four must land readable.
        listOf(0xFF050505.toInt(), 0xFF808080.toInt(), 0xFFEEDDCC.toInt(), 0xFF00FF00.toInt()).forEach { raw ->
            val fixed = CoverAccent.normalise(raw)
            assertTrue("$raw -> ${Integer.toHexString(fixed)}", CoverAccent.inBand(fixed))
            assertEquals("hue is the cover's, only the band is ours", hue(raw), hue(fixed), 2.0)
        }
    }

    @Test fun `ink on a cover accent keeps its contrast`() {
        listOf(0xFF050505.toInt(), 0xFF808080.toInt(), 0xFFEEDDCC.toInt(), 0xFF00FF00.toInt()).forEach { raw ->
            val bg = CoverAccent.normalise(raw)
            val ink = CoverAccent.inkFor(bg)
            val ratio = contrast(bg, ink)
            assertTrue("${Integer.toHexString(bg)} on ${Integer.toHexString(ink)} is $ratio", ratio >= 4.4)
        }
    }

    @Test fun `a real cover image yields its own colour, not the fallback`() {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val f = File(dir, "cover-accent-probe.png")
        // A blue cover with a single saturated orange patch: the patch is what a reader calls "the cover colour".
        val bmp = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(0x2A, 0x4A, 0x5F))
            drawRect(60f, 90f, 180f, 210f, Paint().apply { color = Color.rgb(0xE0, 0x6A, 0x1E) })
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sampled = CoverAccent.sample(f)
        assertTrue(sampled != null)
        val fixed = CoverAccent.normalise(sampled!!)
        assertTrue(CoverAccent.inBand(fixed))
        assertTrue("the orange patch, not the blue field", hue(fixed) in 10.0..45.0)
    }

    private fun hue(argb: Int): Double {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        return hsv[0].toDouble()
    }

    /** WCAG relative contrast, the same measure the accents were hand-checked against. */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }
        val la = lum(a)
        val lb = lum(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    @Test fun `the cover accent is a setting, so it survives the file`() {
        val s = app.kairn.data.AppSettings(accent = Accent.MATCH)
        val back = app.kairn.data.AppSettings.from(org.json.JSONObject(s.toJson().toString()))
        assertEquals(Accent.MATCH, back.accent)
        assertNotEquals(Accent.MATCH, Accent.MINT)
    }
}
