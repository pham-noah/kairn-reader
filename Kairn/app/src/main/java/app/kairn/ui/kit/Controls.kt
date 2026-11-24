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
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.Glass
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface
import app.kairn.ui.theme.readerUi

/** Chip: 44dp pill; selected = light fill with dark ink. */
@Composable
fun KChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val shape = RoundedCornerShape(999.dp)
    val surface =
        if (glass && selected) Modifier.glassSurface(shape, fill = Color(0xEBF0F4F6), elevation = 5.dp, hairline = Color(0x40FFFFFF))
        else if (glass) Modifier.glassSurface(shape, fill = Glass.fill, elevation = 4.dp)
        else Modifier.background(if (selected) K.txt else K.surf).border(1.dp, if (selected) K.txt else K.line, shape)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.heightIn(min = 48.dp).then(surface).pressFeedback(interaction, pressedScale = 0.95f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(label, style = KType.title.copy(fontWeight = FontWeight.SemiBold), color = if (selected) Color(0xFF101316) else K.mut)
        if (count != null) Text("$count", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color(0xFF4A5258) else K.dim)
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
                    .then(if (on) Modifier.background(if (glass) Color(0x33FFFFFF) else K.mint) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = when { on && glass -> K.txt; on -> K.mintInk; else -> K.mut }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                .border(if (selected) 2.dp else 1.dp, if (selected) K.mint else K.line, shape)
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
                .border(if (selected) 2.dp else 1.dp, if (selected) K.mint else Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Default.Check, null, tint = K.txt, modifier = Modifier.size(16.dp)) }
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) K.txt else K.mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Switch: 52x32 track, 24dp thumb. */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track = animateColorAsState(if (checked) K.mint else if (glass) Color(0x29FFFFFF) else K.switchOff, label = "track")
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
        Box(Modifier.offset { IntOffset(thumbX.value.roundToPx(), 4.dp.roundToPx()) }.size(24.dp).clip(CircleShape).background(if (checked) K.mintInk else Color(0xFFE8ECEE)))
    }
    }
}

/** Radio row (used by restore: merge or replace). */
@Composable
fun KRadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 1.dp).size(22.dp).border(2.dp, if (selected) K.mint else Color(0xFF4A535B), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(K.mint))
        }
        Column { Text(title, style = KType.title.copy(fontWeight = FontWeight.Bold)); Text(subtitle, style = KType.small) }
    }
}

/** Reading-theme swatch (onboarding and the reader's Display sheet). */
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
                .border(if (selected) 2.dp else 1.dp, if (selected) K.mint else if (ui.light) K.grab else Color(0xFF5B646C), CircleShape),
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
    accent: Color = K.mint, onFinished: (() -> Unit)? = null, trackColor: Color = K.track,
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
