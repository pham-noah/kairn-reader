package app.kairn.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Interface = Manrope, book text = Literata. N-01 requires fonts to be bundled, never loaded from the
 * web, and OQ-5 leaves their licences unchecked, so this uses the system families for now. To switch:
 * add manrope.ttf / literata.ttf under res/font and replace the two values below with
 * FontFamily(Font(R.font.manrope, ...)); nothing else in the app needs to change.
 */
object KFonts {
    val sans: FontFamily = FontFamily.SansSerif
    val serif: FontFamily = FontFamily.Serif
}

object KType {
    // Type scale rule: body text is 16sp and nothing user-facing sits below 14sp. The 7-12sp text drawn
    // onto generated placeholder covers is artwork, not UI text, and is not part of this scale.
    val h1 = TextStyle(fontFamily = KFonts.sans, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, lineHeight = 35.sp, color = K.txt)
    val h2 = TextStyle(fontFamily = KFonts.sans, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em, lineHeight = 25.sp, color = K.txt)
    val title = TextStyle(fontFamily = KFonts.sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = K.txt)
    val body = TextStyle(fontFamily = KFonts.sans, fontSize = 16.sp, lineHeight = 22.sp, color = K.txt)
    val small = TextStyle(fontFamily = KFonts.sans, fontSize = 14.sp, lineHeight = 20.sp, color = K.mut)
    val label = TextStyle(fontFamily = KFonts.sans, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = K.mut)   // group labels
    val button = TextStyle(fontFamily = KFonts.sans, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val quote = TextStyle(fontFamily = KFonts.serif, fontSize = 16.sp, lineHeight = 24.sp, color = K.txt)
    val onboardingTitle = TextStyle(fontFamily = KFonts.sans, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, lineHeight = 41.sp, color = K.txt)
}
