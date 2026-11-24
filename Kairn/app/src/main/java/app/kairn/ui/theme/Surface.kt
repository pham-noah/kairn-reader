package app.kairn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairn.data.Ambient

/**
 * Glass (default, DEC-5): translucent panels carrying a specular sheen gradient, a hairline highlight and a soft drop shadow, over an ambient colour wash behind the app.
 * Compose has no backdrop blur - it can only blur a view's own content, not what is behind it - so Glass keeps the look of the glass (sheen, highlight, depth, translucency) without true background blur.
 */
enum class SurfaceStyle { Glass, Solid }

/** The style currently in force, provided once at the theme root from settings. */
val LocalSurfaceStyle = staticCompositionLocalOf { SurfaceStyle.Glass }

/** The ambient wash colour, provided once at the theme root from settings. */
val LocalAmbient = staticCompositionLocalOf { Ambient.MINT }

/**
 * Whether a touch may be answered with a tick. Lives here beside the other shell-wide facts the kit reads, so a
 * control deep in a row never has to be handed a flag to know whether it is allowed to buzz.
 */
val LocalHaptics = staticCompositionLocalOf { true }

/** True when the app is drawing the Glass style. Reads a CompositionLocal, so it needs a composable scope. */
val glass: Boolean @Composable get() = LocalSurfaceStyle.current == SurfaceStyle.Glass

/** Colour + geometry tokens for the glass surfaces. */
object Glass {
    /** Panel fill. Deliberately a DARK translucent tint, not a white one: with no backdrop blur, a white tint over a dark page reads as a hazy white box. */
    val panelFill = Color(0x9E262C33)     // ~62% of the dark surface colour
    val fill = Color(0x14FFFFFF)          // subtle fill for small controls (chips, buttons)
    val well = Color(0x42000000)          // rgba(0,0,0,.26) inset wells: fields, tracks
    val hairline = Color(0x14FFFFFF)      // very subtle edge (~8% white), close to the fill - never a bright outline

    /** Sheen: a light edge along the TOP that fades out by mid-panel, plus a faint lift at the bottom. Deliberately top-concentrated and subtle - Compose has no backdrop blur, and a full-surface white gradient turns every large card into a pale grey box. */
    val sheen = Brush.verticalGradient(
        0f to Color(0x0DFFFFFF),
        0.35f to Color(0x00FFFFFF),
        0.9f to Color(0x00FFFFFF),
        1f to Color(0x08FFFFFF),
    )

    /** The four-tint ambient wash. */
    val mintBlooms = listOf(
        // fractionX, fractionY, radiusFraction, colour
        floatArrayOf(0.06f, 0.02f, 0.85f) to Color(0x337EE0C3),   // mint, top-left
        floatArrayOf(1.00f, 0.34f, 0.75f) to Color(0x2B80B5EA),   // sky, right
        floatArrayOf(0.14f, 0.78f, 0.90f) to Color(0x24E68B9B),   // rose, lower-left
        floatArrayOf(0.96f, 1.00f, 0.70f) to Color(0x21E8B45C),   // amber, bottom-right
    )

    /**
     * The blooms for a chosen wash. PLAIN returns none, so the base colour shows alone.
     *
     * [strength] dims the wash without moving the blooms: Solid panels carry no sheen or translucency, so at full
     * strength the same four blooms read as a lamp behind a flat card instead of a tint on a backdrop.
     */
    fun blooms(ambient: Ambient, strength: Float = 1f): List<Pair<FloatArray, Color>> {
        val pick = when (ambient) {
            Ambient.MINT -> mintBlooms
            Ambient.OCEAN -> listOf(
                floatArrayOf(0.10f, 0.05f, 0.90f) to Color(0x3380B5EA),
                floatArrayOf(0.95f, 0.40f, 0.85f) to Color(0x2A62C9D6),
                floatArrayOf(0.20f, 0.85f, 0.80f) to Color(0x227EE0C3),
                floatArrayOf(1.00f, 1.00f, 0.70f) to Color(0x1E4F86C6),
            )
            Ambient.SUNSET -> listOf(
                floatArrayOf(0.08f, 0.06f, 0.88f) to Color(0x33E8B45C),
                floatArrayOf(0.98f, 0.30f, 0.82f) to Color(0x2FE68B9B),
                floatArrayOf(0.16f, 0.82f, 0.86f) to Color(0x27C86A8A),
                floatArrayOf(1.00f, 1.00f, 0.72f) to Color(0x1FD98A48),
            )
            Ambient.PLAIN -> emptyList()
        }
        return if (strength == 1f) pick else pick.map { (f, c) -> f to c.copy(alpha = (c.alpha * strength).coerceIn(0f, 1f)) }
    }
}

/**
 * How much of the wash Solid keeps. Glass sits ON the wash (its panels are translucent, so the colour reaches them);
 * a Solid panel is opaque and only the gaps between cards show it, so the same alphas would read as a lamp behind
 * the UI. Measured against the base: at 0.55 the wash is a tint you notice at the edges, not a colour scheme.
 */
const val SOLID_WASH = 0.55f

/**
 * Paints the ambient colour wash as the backdrop of this node. Uses drawWithCache so the four radial
 * gradients are built once per size rather than per frame. Reads the ambient choice, so it must be
 * called from a composable scope.
 */
@Composable
fun Modifier.ambientWash(strength: Float = 1f): Modifier {
    val ambient = LocalAmbient.current
    return this.drawWithCache {
        val maxDim = maxOf(size.width, size.height)
        val base = K.bg
        val brushes = Glass.blooms(ambient, strength).map { (f, color) ->
            Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(f[0] * size.width, f[1] * size.height),
                radius = f[2] * maxDim * 0.75f,
            )
        }
        onDrawWithContent {
            drawRect(base)
            brushes.forEach { drawRect(it) }
            drawContent()
        }
    }
}

/**
 * The shared glass recipe: a dark translucent fill, a faint top sheen, a hairline edge and a soft drop shadow.
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Color = Glass.panelFill,
    elevation: Dp = 8.dp,
    hairline: Color = Glass.hairline,
): Modifier = this
    .glassShadow(elevation, shape = shape)
    .clip(shape)
    .background(fill, shape)
    .background(Glass.sheen, shape)
    .border(1.dp, hairline, shape)

/** A soft drop shadow in a SINGLE draw pass: a gradient brush built once per size (drawWithCache) and painted as one rect, so it allocates nothing per frame and adds no offscreen RenderNode. */
fun Modifier.glassShadow(elevation: Dp, color: Color = Color.Black, shape: Shape = RectangleShape): Modifier =
    if (elevation.value <= 0f) this else this.drawWithCache {
        val el = elevation.toPx()
        // Inset by (most of) the corner radius, so a pill or circle gets no square shadow corners past its curve.
        val r = (shape.createOutline(size, layoutDirection, this) as? Outline.Rounded)?.roundRect?.bottomLeftCornerRadius?.x ?: 0f
        val inset = (r * 0.6f).coerceAtMost(size.width / 2f)
        val brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0.10f), Color.Transparent),
            startY = size.height,
            endY = size.height + el,
        )
        onDrawBehind {
            drawRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(inset, size.height),
                size = Size(size.width - 2f * inset, el),
            )
        }
    }

/**
 * Solid: the same panel weight as Glass, arrived at the other way. An opaque fill with a vertical fall-off (the top
 * of a milled panel catches the light, the bottom turns away), a hairline that is brighter at the top edge than at
 * the bottom, and a tight shadow instead of a soft one. Flat grey plus a 1dp outline was the whole of Solid before,
 * and it read as a box drawn on the background rather than a surface with weight.
 */
fun Modifier.solidSurface(shape: Shape, fill: Color = K.surf, elevation: Dp = 4.dp): Modifier = this
    .glassShadow(elevation, shape = shape)
    .clip(shape)
    .background(
        Brush.verticalGradient(0f to lerp(fill, Color.White, 0.055f), 0.55f to fill, 1f to lerp(fill, Color.Black, 0.03f)),
        shape,
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            0f to Color(0x26FFFFFF), 0.55f to K.line, 1f to K.line.copy(alpha = 0.55f),
        ),
        shape,
    )

/** The Solid well: darker than the panel and shadowed from the top, so it reads as cut into the card. */
fun Modifier.solidWell(shape: Shape): Modifier = this
    .clip(shape)
    .background(K.bg)
    .border(1.dp, Brush.verticalGradient(0f to Color(0x38000000), 1f to K.line), shape)

/** A well (inset) surface: fields, segmented tracks - darker than the panel, no shadow. */
fun Modifier.glassWell(shape: Shape): Modifier = this
    .clip(shape)
    .background(Glass.well, shape)
    .border(1.dp, Glass.hairline, shape)
