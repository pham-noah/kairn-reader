package app.kairn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import app.kairn.data.Ambient

// Constant: built once, not on every recomposition of the theme root.
private val KairnColors = darkColorScheme(
    primary = K.mint, onPrimary = K.mintInk, background = K.bg, onBackground = K.txt,
    surface = K.surf, onSurface = K.txt, surfaceVariant = K.surf2, onSurfaceVariant = K.mut,
    outline = K.line, error = K.danger, surfaceContainer = K.surf, surfaceContainerHigh = K.surf2,
)
private val KairnShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(Dim.card.dp), large = RoundedCornerShape(Dim.sheet.dp),
)

/** App chrome is always the dark base with one mint accent; only the reader page changes theme. */
@Composable
fun KairnTheme(glass: Boolean = true, ambient: Ambient = Ambient.MINT, content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalSurfaceStyle provides if (glass) SurfaceStyle.Glass else SurfaceStyle.Solid,
    LocalAmbient provides ambient,
) {
    MaterialTheme(
        colorScheme = KairnColors,
        shapes = KairnShapes,
        content = content,
    )
}
