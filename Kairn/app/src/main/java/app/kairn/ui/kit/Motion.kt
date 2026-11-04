package app.kairn.ui.kit

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * Press feedback: the surface sinks a little under the finger and comes back on release.
 *
 * 90 ms down, 90 ms up, 4% - enough that the tap feels answered, short enough that a fast double tap never
 * sees the second state. It scales rather than growing a shadow: a scale cannot move anything else on screen,
 * so the row a button sits in never reflows under the finger.
 */
@Composable
fun Modifier.pressFeedback(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 90, easing = LinearOutSlowInEasing),
        label = "press",
    )
    return this.scale(scale)
}
