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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Row(
        modifier.heightIn(min = 48.dp).then(surface)
            .clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }
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
            Box(
                Modifier.weight(1f).heightIn(min = Dim.tap.dp).clip(shape)
                    .then(if (on) Modifier.background(if (glass) Color(0x33FFFFFF) else K.mint) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(o, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = when { on && glass -> K.txt; on -> K.mintInk; else -> K.mut }, maxLines = 1) }
        }
    }
}

/** Switch: 52x32 track, 24dp thumb. */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track = animateColorAsState(if (checked) K.mint else if (glass) Color(0x29FFFFFF) else K.switchOff, label = "track")
    val thumbX = animateDpAsState(if (checked) 24.dp else 4.dp, label = "thumb")
    // The 52x32 track is the LOOK; the tap target is the box around it, which must be the 48dp
    // minimum. The toggleable goes on the OUTER box, or the target is only the 32dp-tall track.
    Box(
        modifier.heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
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
    Column(modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(ui.page)
                // A near-black page on a near-black sheet has no edge of its own: the unselected ring has to
                // carry the shape, so a dark swatch gets a lighter one than a light swatch does.
                .border(if (selected) 2.dp else 1.dp, if (selected) K.mint else if (ui.light) K.grab else Color(0xFF5B646C), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Default.Check, null, tint = if (ui.light) Color(0xFF14876A) else K.mint) }
        Text(theme.name.lowercase().replaceFirstChar(Char::uppercase), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) K.txt else K.mut)
    }
}

/** Slider: 6dp track, 24dp pale thumb. [accent] is amber for the warm-light slider. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f, modifier: Modifier = Modifier, accent: Color = K.mint, onFinished: (() -> Unit)? = null, trackColor: Color = K.track) {
    Slider(
        value, onChange, modifier.heightIn(min = 32.dp), valueRange = range, onValueChangeFinished = onFinished,
        colors = SliderDefaults.colors(thumbColor = Color(0xFFF1F4F5), activeTrackColor = accent, inactiveTrackColor = trackColor),
        thumb = { Box(Modifier.size(24.dp).shadow(2.dp, CircleShape).background(Color(0xFFF1F4F5), CircleShape)) },
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
