package app.kairn.data

import kotlin.math.abs

/**
 * Three ways a page can look, written as values rather than described in a sentence.
 *
 * Readers were being asked to tune five sliders - size, leading, paragraph gap, margins, and then to judge the
 * result on a page they had to leave Settings to see. These are the three answers people actually give ("smaller",
 * "normal", "bigger than that"), and each one moves the four numbers that make the answer true.
 *
 * [of] runs backwards so the tiles can show which preset a reader is currently on, and so that nudging any of the
 * four sliders by hand simply stops any tile being selected - the presets describe the sliders, they do not own them.
 */
enum class ReadingPreset(
    val fontScale: Double,
    val lineHeight: Double,
    val paragraphSpacing: Double,
    val margins: Double,
) {
    COMPACT(0.88, 1.34, 0.30, 1.45),
    COMFORTABLE(1.0, 1.5, 0.5, 1.0),
    LARGE(1.34, 1.78, 0.85, 0.66),
    ;

    /** The prefs with this preset's four numbers in place; everything else the reader chose is left alone. */
    fun appliedTo(p: ReaderPrefs): ReaderPrefs =
        p.copy(fontScale = fontScale, lineHeight = lineHeight, paragraphSpacing = paragraphSpacing, margins = margins)

    companion object {
        /** Which preset these numbers are, or null once a slider has been moved to something that is not one. */
        fun of(p: ReaderPrefs): ReadingPreset? = entries.firstOrNull {
            abs(p.fontScale - it.fontScale) < 0.02 && abs(p.lineHeight - it.lineHeight) < 0.02 &&
                abs(p.paragraphSpacing - it.paragraphSpacing) < 0.02 && abs(p.margins - it.margins) < 0.02
        }
    }
}
