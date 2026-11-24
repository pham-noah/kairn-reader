package app.kairn.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.data.Ambient
import app.kairn.data.ThemeId
import app.kairn.ui.theme.A
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.Glass
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface
import app.kairn.ui.theme.readerUi
import app.kairn.ui.theme.solidSurface
import app.kairn.data.Accent
import app.kairn.ui.theme.accentPalette
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp

/**
 * Chip: 44dp pill; selected = light fill with dark ink.
 *
 * [centered] is for chips that share a row by weight rather than by their own width, where a naturally sized pill
 * would leave the label hanging at its left edge. It centres the label and count as one group and drops the
 * padding to 10dp, which is what lets three of them hold Want / Reading / Finished on one 411dp line.
 */
@Composable
fun KChip(
    label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null,
    centered: Boolean = false,
) {
    val shape = RoundedCornerShape(999.dp)
    val surface =
        if (glass && selected) Modifier.glassSurface(shape, fill = Color(0xEBF0F4F6), elevation = 5.dp, hairline = Color(0x40FFFFFF))
        else if (glass) Modifier.glassSurface(shape, fill = Glass.fill, elevation = 4.dp)
        else Modifier.solidSurface(shape, fill = if (selected) K.txt else K.surf, elevation = 3.dp)
    val interaction = remember { MutableInteractionSource() }
    // Chips had no haptics at all: nine call sites fired and this one, the control the library is made of, was
    // silent. A filter chip that changes what the shelf holds is at least the event a button's tick is for.
    val haptics = rememberHaptics()
    Row(
        modifier.heightIn(min = 48.dp).then(surface).pressFeedback(interaction, pressedScale = 0.95f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = { haptics.tick(); onClick() })
            .semantics { this.selected = selected }
            .padding(horizontal = if (centered) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = KType.title.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) Color(0xFF101316) else K.mut,
                maxLines = if (centered) 1 else Int.MAX_VALUE, softWrap = !centered,                overflow = if (centered) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
            if (count != null) Text("$count", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color(0xFF4A5258) else K.dim)
        }
    }
}

/** Segmented control: pill track with a mint selected segment. */
@Composable
fun KSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    val seg = modifier.fillMaxWidth().clip(shape).padding(3.dp)
    val track = if (glass) seg.background(Glass.well) else seg.background(K.bg).border(1.dp, K.line, shape)
    Row(track, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val segInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).heightIn(min = Dim.tap.dp).clip(shape)
                    .pressFeedback(segInteraction, pressedScale = 0.97f)
                    .then(if (on) Modifier.background(if (glass) Color(0x33FFFFFF) else A.accent) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = when { on && glass -> K.txt; on -> A.ink; else -> K.mut }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * Picking tile: a DRAWN preview with its name under it, and a mint ring when chosen.
 *
 * The settings this replaces are ones a word cannot carry: "Glass or Solid" is opaque unless you can see the
 * panel, and "Glass"/"Solid" in a pill look identical either way. The preview is whatever the caller draws, so
 * the tile shows the real recipe rather than a picture of it.
 */
@Composable
fun KOptionTile(
    label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    height: Int = 60, preview: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Column(
        modifier.heightIn(min = Dim.tap.dp).pressFeedback(interaction, pressedScale = 0.96f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.RadioButton) {
                if (!selected) haptics.tick()
                onClick()
            }
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(height.dp).clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) A.accent else K.line, shape)
                .padding(3.dp),
            contentAlignment = Alignment.Center, content = preview,
        )
        // Two lines, not one: three tiles across a phone leaves "Schedule" and スケジュール no room on a single
        // line at large type, and a clipped label is a tile with no name.
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) K.txt else K.mut, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, lineHeight = 15.sp,
        )
    }
}

/**
 * Ambient swatch: the wash ITSELF in a circle - the same four blooms the backdrop paints, over the app's base
 * colour. PLAIN draws the bare base, which is exactly what it means.
 */
@Composable
fun KAmbientSwatch(ambient: Ambient, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 2.2x the backdrop's own alphas: 20% colour over 44dp of screen is a smudge, and the swatch has one job -
    // telling four washes apart.
    val blooms = remember(ambient) { Glass.blooms(ambient, 2.2f) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = Dim.tap.dp).pressFeedback(interaction, pressedScale = 0.94f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .drawBehind {
                    drawRect(K.bg)
                    val maxDim = maxOf(size.width, size.height)
                    blooms.forEach { (f, color) ->
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(color, Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(f[0] * size.width, f[1] * size.height),
                                radius = f[2] * maxDim * 0.75f,
                            ),
                        )
                    }
                }
                .border(if (selected) 2.dp else 1.dp, if (selected) A.accent else Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Default.Check, null, tint = K.txt, modifier = Modifier.size(16.dp)) }
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) K.txt else K.mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Switch: 52x32 track, 24dp thumb. */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track = animateColorAsState(if (checked) A.accent else if (glass) Color(0x29FFFFFF) else K.switchOff, label = "track")
    val thumbX = animateDpAsState(if (checked) 24.dp else 4.dp, label = "thumb")
    // The 52x32 track is the LOOK; the tap target is the box around it, which must be the 48dp
    // minimum. The toggleable goes on the OUTER box, or the target is only the 32dp-tall track.
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 48.dp).toggleable(
            value = checked, role = Role.Switch,
            // The tick belongs to the value changing, not to the finger arriving: a tap that lands on the same
            // value again should not buzz as if something happened.
            onValueChange = { v -> if (v != checked) haptics.toggle(); onChange(v) },
        ),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        Modifier.size(52.dp, 32.dp).clip(CircleShape).background(track.value),
    ) {
        // No Modifier.shadow on the thumb: it allocates a RenderNode, and there is one per switch. The
        Box(Modifier.offset { IntOffset(thumbX.value.roundToPx(), 4.dp.roundToPx()) }.size(24.dp).clip(CircleShape).background(if (checked) A.ink else Color(0xFFE8ECEE)))
    }
    }
}

/** Radio row (used by restore: merge or replace). */
@Composable
fun KRadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).border(2.dp, if (selected) A.accent else Color(0xFF4A535B), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(A.accent))
        }
        Column { Text(title, style = KType.title.copy(fontWeight = FontWeight.Bold)); Text(subtitle, style = KType.small) }
    }
}

/** Reading-theme swatch (onboarding and the reader's Display sheet). */
/**
 * One accent, as the colour itself. The dot is filled with the accent rather than outlined in it, because the choice
 * here is a fill - and the check sits in the accent's own ink, which is the pair the app will actually use.
 */
@Composable
fun KAccentSwatch(
    accent: Accent, label: String, selected: Boolean, onClick: () -> Unit,
    showLabel: Boolean = true, modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val palette = remember(accent) { accentPalette(accent) }
    // "From the cover" has no colour of its own to draw, so the dot is a sweep of the five - the same wheel a cover
    // is sampled from. A solid dot would claim a colour the setting does not have.
    val fill: androidx.compose.ui.graphics.Brush = if (accent == Accent.MATCH) {
        Brush.sweepGradient(listOf(Color(0xFF7EE0C3), Color(0xFF80B5EA), Color(0xFFC3A6F0), Color(0xFFE68B9B), Color(0xFFE8B45C), Color(0xFF7EE0C3)))
    } else {
        androidx.compose.ui.graphics.SolidColor(palette.color)
    }
    val ink = if (accent == Accent.MATCH) Color(0xFF0B0E10) else palette.ink
    Column(
        modifier.heightIn(min = Dim.tap.dp)
            .clickable(role = Role.RadioButton) { haptics.tick(); onClick() }
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = ink, modifier = Modifier.size(22.dp))
        }
        if (showLabel) Text(label, style = KType.small, color = if (selected) K.txt else K.dim)
    }
}

@Composable
fun KThemeSwatch(theme: ThemeId, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = readerUi(theme)
    val dotInteraction = remember { MutableInteractionSource() }
    Column(modifier.heightIn(min = 48.dp).pressFeedback(dotInteraction, pressedScale = 0.93f)
        .clickable(interactionSource = dotInteraction, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(ui.page)
                // A near-black page on a near-black sheet has no edge of its own: the unselected ring has to
                // carry the shape, so a dark swatch gets a lighter one than a light swatch does.
                .border(if (selected) 2.dp else 1.dp, if (selected) A.accent else if (ui.light) K.grab else Color(0xFF5B646C), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Default.Check, null, tint = ui.accent) }
        Text(theme.name.lowercase().replaceFirstChar(Char::uppercase), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) K.txt else K.mut)
    }
}

/** Slider: 6dp track, 24dp pale thumb. [accent] is amber for the warm-light slider. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KSlider(
    value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f, modifier: Modifier = Modifier,
    accent: Color = A.accent, onFinished: (() -> Unit)? = null, trackColor: Color = K.track,
    description: String? = null, stateText: String? = null,
    /**
     * The knob. Defaults to the accent, because a knob is the one part of a slider that must be findable at a
     * glance; it used to be a fixed near-white, which vanished on the light and sepia reader chrome the moment
     * the slider stopped sitting on dark glass.
     */
    thumbColor: Color = accent,
) {
    Slider(
        value, onChange,
        modifier.heightIn(min = 32.dp).then(
            if (description == null) Modifier
            else Modifier.semantics { contentDescription = description; stateDescription = stateText.orEmpty() },
        ),
        valueRange = range, onValueChangeFinished = onFinished,
        colors = SliderDefaults.colors(thumbColor = thumbColor, activeTrackColor = accent, inactiveTrackColor = trackColor),
        thumb = { Box(Modifier.size(24.dp).shadow(2.dp, CircleShape).background(thumbColor, CircleShape)) },
        // Material draws a stop indicator at the far end of the rail - a lone dot that, next to this 24dp knob,
        // reads as a second thumb sitting at the maximum. The rail is drawn here instead: one capsule filled to
        // the value, nothing else on it.
        track = { st ->
            val span = range.endInclusive - range.start
            val f = if (span <= 0f) 0f else ((st.value - range.start) / span).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(trackColor),
            ) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(accent)) }
        },
    )
}

/** Label on the left, live value on the right, then the slider. */
@Composable
fun KControl(label: String, valueText: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = KType.label.copy(color = K.txt)); Text(valueText, style = KType.small)
        }
        content()
    }
}

/**
 * One preset, drawn: a glyph that shows the thing itself beside its name.
 *
 * The reading presets put four numbers on four sliders; this is the same choice as a page whose lines are the size
 * and spacing it will give you. The bar presets draw the bar. A tile that has to be read out of a caption is the
 * defect this replaces.
 *
 * [wide] is for large type: three across a phone cannot hold "Comfortable" at 2.0x - it ellipsised to "Comfor..." in
 * the shot - so past 1.5x the row becomes a column of full-width tiles with the glyph on the left and the name with
 * room to wrap.
 */
@Composable
fun KPresetTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    glyph: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val accent = A.accent
    val fill = if (selected) lerp(K.surf2, accent, 0.10f) else K.surf
    Column(
        modifier
            .clip(RoundedCornerShape(Dim.card.dp))
            .solidSurface(RoundedCornerShape(Dim.card.dp), fill = fill, elevation = if (selected) 2.dp else 4.dp)
            .then(
                if (selected) Modifier.border(1.dp, accent, RoundedCornerShape(Dim.card.dp)) else Modifier
            )
            .clickable(role = Role.RadioButton) { haptics.tick(); onClick() }
            .semantics { this.selected = selected }
            .padding(
                start = if (wide) 14.dp else 6.dp,
                end = if (wide) 14.dp else 6.dp,
                top = if (wide) 8.dp else 10.dp,
                bottom = if (wide) 8.dp else 10.dp,
            ),
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 52.dp, height = 44.dp), contentAlignment = Alignment.Center) { glyph() }
                Text(
                    label, style = KType.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (selected) K.txt else K.mut, modifier = Modifier.padding(start = 12.dp),
                )
            }
        } else {
            Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { glyph() }
            Text(
                label, style = KType.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, color = if (selected) K.txt else K.mut,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A page at the size, leading and margin the preset gives: the glyph is the setting. */
@Composable
fun PageGlyph(fontScale: Double, lineHeight: Double, margins: Double, modifier: Modifier = Modifier) {
    val ink = K.mut
    val edge = K.line
    Canvas(modifier.size(width = 40.dp, height = 52.dp)) {
        val unit = size.height / 52f
        val margin = margins.coerceIn(0.5, 2.5).toFloat()
        val pad = size.width * (0.30f - (margin - 0.5f) / 2f * 0.16f)
        val scale = fontScale.coerceIn(0.7, 2.5).toFloat()
        val stroke = (2.2f + (scale - 0.7f) / 1.8f * 3.4f) * unit
        val gap = stroke * (0.75f + lineHeight.toFloat() * 0.55f)
        // The page, then four lines: thickness is the type size, gap is the leading, and the inset is the margin.
        drawRoundRect(
            color = edge, topLeft = Offset(0f, 0f), size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * unit, 3.5f * unit),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * unit),
        )
        var y = pad * 0.9f + stroke
        var i = 0
        while (y < size.height - pad * 0.5f) {
            // The last line of a paragraph is short, the way a real page breaks.
            val w = if (i == 3) size.width - pad * 2 - (size.width - pad * 2) * 0.34f else size.width - pad * 2
            drawRoundRect(
                color = ink, topLeft = Offset(pad, y),
                size = Size(w, stroke),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 0.5f, stroke * 0.5f),
            )
            y += stroke + gap
            i++
        }
    }
}

/**
 * The bar, drawn: the first two slots lit and carrying the mark of the tool that is in them, then More.
 *
 * The first version lit two pills whichever preset it was asked to draw, which is three pictures of the same thing -
 * the labels were doing all the work. Each slot now carries its tool's mark, so the three arrangements differ for
 * the reason they differ in the app: Contents and Search in one, Read aloud and Auto scroll in another.
 */
@Composable
fun BarGlyph(order: List<app.kairn.data.BarItem>, hidden: Set<app.kairn.data.BarItem>, modifier: Modifier = Modifier) {
    val accent = A.accent
    val shown = order.filterNot { it in hidden }
    val slots = shown.take(app.kairn.data.BarItem.MAIN_SLOTS)
    val more = shown.size - slots.size
    Canvas(modifier.size(width = 52.dp, height = 40.dp)) {
        val unit = size.width / 52f
        val h = 14f * unit
        val r = h * 0.30f
        val cy = size.height / 2
        // The bar itself.
        drawRoundRect(
            color = K.line, topLeft = Offset(0f, cy - h / 2), size = Size(size.width, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        val moreW = if (more > 0) 10f * unit else 0f
        val slotW = (size.width - 6f * unit - moreW) / slots.size.coerceAtLeast(1)
        val mark = lerp(K.surf, Color(0xFF000000), 0.55f)
        var x = 3f * unit
        slots.forEach { item ->
            drawRoundRect(
                color = accent, topLeft = Offset(x, cy - h * 0.40f), size = Size(slotW - 2f * unit, h * 0.80f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.7f, r * 0.7f),
            )
            drawBarMark(item, Offset(x + (slotW - 2f * unit) / 2, cy), h * 0.30f, unit, mark)
            x += slotW
        }
        // Everything past the two slots waits behind More, and says how much is waiting.
        if (more > 0) {
            repeat(minOf(more, 3)) {
                drawCircle(K.dim, radius = 1.4f * unit, center = Offset(x + 4f * unit, cy + (it - 1) * 3.2f * unit))
            }
        }
    }
}

/** One mark per tool, small enough to sit inside a 14dp pill and distinct enough to tell the three presets apart. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBarMark(
    item: app.kairn.data.BarItem,
    c: Offset,
    s: Float,
    unit: Float,
    ink: Color,
) {
    val w = 1.3f * unit
    when (item) {
        // Three short lines: a table of contents.
        app.kairn.data.BarItem.CONTENTS -> repeat(3) {
            val y = c.y - s * 0.55f + it * s * 0.55f
            drawLine(ink, Offset(c.x - s * 0.7f, y), Offset(c.x + s * 0.7f, y), w)
        }
        // A lens with a handle.
        app.kairn.data.BarItem.SEARCH -> {
            drawCircle(ink, radius = s * 0.45f, center = Offset(c.x - s * 0.15f, c.y - s * 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawLine(ink, Offset(c.x + s * 0.2f, c.y + s * 0.2f), Offset(c.x + s * 0.7f, c.y + s * 0.7f), w)
        }
        // A speaker: the triangle.
        app.kairn.data.BarItem.READ_ALOUD -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - s * 0.6f, c.y - s * 0.30f)
                lineTo(c.x - s * 0.1f, c.y - s * 0.30f)
                lineTo(c.x + s * 0.55f, c.y - s * 0.75f)
                lineTo(c.x + s * 0.55f, c.y + s * 0.75f)
                lineTo(c.x - s * 0.1f, c.y + s * 0.30f)
                lineTo(c.x - s * 0.6f, c.y + s * 0.30f)
                close()
            }
            drawPath(path, ink)
        }
        // A capital A: two legs and a bar.
        app.kairn.data.BarItem.DISPLAY -> {
            drawLine(ink, Offset(c.x - s * 0.6f, c.y + s * 0.6f), Offset(c.x, c.y - s * 0.65f), w)
            drawLine(ink, Offset(c.x + s * 0.6f, c.y + s * 0.6f), Offset(c.x, c.y - s * 0.65f), w)
            drawLine(ink, Offset(c.x - s * 0.32f, c.y + s * 0.12f), Offset(c.x + s * 0.32f, c.y + s * 0.12f), w)
        }
        // An arrow running down a page.
        app.kairn.data.BarItem.AUTO_SCROLL -> {
            drawLine(ink, Offset(c.x, c.y - s * 0.75f), Offset(c.x, c.y + s * 0.5f), w)
            drawLine(ink, Offset(c.x - s * 0.4f, c.y + s * 0.1f), Offset(c.x, c.y + s * 0.55f), w)
            drawLine(ink, Offset(c.x + s * 0.4f, c.y + s * 0.1f), Offset(c.x, c.y + s * 0.55f), w)
        }
        // A note: a box with a line under it.
        app.kairn.data.BarItem.NOTES -> {
            drawRoundRect(
                ink, topLeft = Offset(c.x - s * 0.6f, c.y - s * 0.6f), size = Size(s * 1.2f, s * 1.05f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.2f, s * 0.2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w),
            )
            drawLine(ink, Offset(c.x - s * 0.3f, c.y + s * 0.7f), Offset(c.x + s * 0.5f, c.y + s * 0.7f), w)
        }
    }
}
