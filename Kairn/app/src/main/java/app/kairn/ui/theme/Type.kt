@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.kairn.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairn.R

/**
 * Interface = Manrope, book text = Literata.
 * Both are bundled (N-01: fonts never load from the web) under the SIL Open Font License; the licence texts are in licences/ at the repository root.
 * FontVariation needs API 26, the app's minimum.
 */
object KFonts {
    private fun weight(id: Int, w: Int): Font = Font(
        resId = id,
        weight = FontWeight(w),
        variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )

    val sans: FontFamily = FontFamily(
        weight(R.font.manrope, 400),
        weight(R.font.manrope, 500),
        weight(R.font.manrope, 600),
        weight(R.font.manrope, 700),
        weight(R.font.manrope, 800),
    )

    val serif: FontFamily = FontFamily(
        weight(R.font.literata, 400),
        weight(R.font.literata, 600),
        weight(R.font.literata, 700),
    )
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
