package app.kairn.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface
import app.kairn.ui.theme.glassWell

@Composable
fun KCard(modifier: Modifier = Modifier, padding: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(if (glass) 26.dp else Dim.card.dp)
    val surface = if (glass) modifier.fillMaxWidth().glassSurface(shape)
                  else modifier.fillMaxWidth().clip(shape).background(K.surf).border(1.dp, K.line, shape)
    Column(surface.padding(padding.dp), content = content)
}

/** Round 4 `.group`: a small bold label above a card or pill group. */
@Composable
fun KGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 22.dp)) {
        Text(label, style = KType.label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        content()
    }
}

/** Round 4 `.row`: leading icon, title + subtitle, trailing slot. Hairline above when [divider]. */
@Composable
fun KRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: ImageVector? = null,
    divider: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier) {
        if (divider) HorizontalDivider(color = K.line)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) Icon(leading, null, tint = K.mut, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = KType.title)
                if (subtitle != null) Text(subtitle, style = KType.small)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun KChevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = K.dim, modifier = Modifier.size(20.dp))

/** Text input in the round 4 `.field` style. */
@Composable
fun KField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", label: String? = null, singleLine: Boolean = true, leading: ImageVector? = null, minLines: Int = 1) {
    Column(modifier) {
        if (label != null) Text(label, style = KType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
        OutlinedTextField(
            value, onChange,
            if (glass) Modifier.fillMaxWidth().glassWell(RoundedCornerShape(if (leading != null && singleLine) 999.dp else Dim.field.dp))
            else Modifier.fillMaxWidth().heightIn(min = 46.dp),
            singleLine = singleLine, minLines = minLines,
            placeholder = { Text(placeholder, color = K.dim) },
            leadingIcon = leading?.let { { Icon(it, null, tint = K.dim) } },
            shape = RoundedCornerShape(if (leading != null && singleLine) 999.dp else Dim.field.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (glass) Color.Transparent else K.bg,
                unfocusedContainerColor = if (glass) Color.Transparent else K.bg,
                focusedBorderColor = if (glass) Color.Transparent else K.mint,
                unfocusedBorderColor = if (glass) Color.Transparent else K.line,
                cursorColor = K.mint, focusedTextColor = K.txt, unfocusedTextColor = K.txt,
            ),
        )
    }
}
