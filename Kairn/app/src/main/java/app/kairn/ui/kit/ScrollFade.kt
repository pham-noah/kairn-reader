package app.kairn.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the ending edge of a row that scrolls sideways.
 *
 * A chip clipped by the screen edge reads as a control that failed to fit, not as a row with more in it — and the
 * library's filters are exactly that: four statuses, cut after "Fir". The fade costs no layout, sits over the
 * last 28dp of the row, and disappears when there is nothing left to scroll to.
 */
@Composable
fun Modifier.scrollFade(color: Color = Color(0xFF0E1114), width: Dp = 28.dp): Modifier {
    val band = with(LocalDensity.current) { width.toPx() }
    return this.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, color), startX = size.width - band, endX = size.width),
            topLeft = Offset(size.width - band, 0f),
            size = Size(band, size.height),
        )
    }
}
