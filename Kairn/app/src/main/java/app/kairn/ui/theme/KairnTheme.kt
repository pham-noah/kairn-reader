package app.kairn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import app.kairn.data.Accent
import app.kairn.data.Ambient

private val KairnShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(Dim.card.dp), large = RoundedCornerShape(Dim.sheet.dp),
)

/** App chrome is always the dark base; the accent is the one colour the setting moves. */
@Composable
fun KairnTheme(
    glass: Boolean = true, ambient: Ambient = Ambient.MINT, haptics: Boolean = true,
    accent: Accent = Accent.MINT,
    // Only meaningful for Accent.MATCH: the colour CoverAccent read off the book being shown.
    coverAccent: Int? = null,
    content: @Composable () -> Unit,
) {
    val accentPalette = remember(accent, coverAccent) { accentPalette(accent, coverAccent) }
    // Built per accent rather than once at file scope: primary and onPrimary are the accent and its ink, and a
    // cached scheme would keep the first accent for the life of the process.
    val colors = remember(accentPalette) {
        darkColorScheme(
            primary = accentPalette.color, onPrimary = accentPalette.ink, background = K.bg, onBackground = K.txt,
            surface = K.surf, onSurface = K.txt, surfaceVariant = K.surf2, onSurfaceVariant = K.mut,
            outline = K.line, error = K.danger, surfaceContainer = K.surf, surfaceContainerHigh = K.surf2,
        )
    }
    CompositionLocalProvider(
        LocalSurfaceStyle provides if (glass) SurfaceStyle.Glass else SurfaceStyle.Solid,
        LocalAmbient provides ambient,
        LocalHaptics provides haptics,
        LocalAccent provides accentPalette,
    ) {
        MaterialTheme(colorScheme = colors, shapes = KairnShapes, content = content)
    }
}
