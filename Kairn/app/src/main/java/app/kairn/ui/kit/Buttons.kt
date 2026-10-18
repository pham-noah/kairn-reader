package app.kairn.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.Glass
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface

enum class KButtonStyle { Primary, Secondary, Danger, Ghost }

/** Button: pill, 50dp tall (40dp small), full width unless [small]. */
@Composable
fun KButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    style: KButtonStyle = KButtonStyle.Primary, small: Boolean = false,
    icon: ImageVector? = null, enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(999.dp)
    val (bg, fg, border) = when (style) {
        KButtonStyle.Primary -> Triple(K.mint, K.mintInk, null)
        KButtonStyle.Secondary -> Triple(if (glass) Color.Transparent else K.surf2, K.txt, if (glass) Glass.hairline else K.line)
        KButtonStyle.Danger -> Triple(K.danger.copy(alpha = .10f), K.danger, K.danger.copy(alpha = .30f))
        KButtonStyle.Ghost -> Triple(Color.Transparent, K.mut, null)
    }
    val base = modifier.then(if (small) Modifier else Modifier.fillMaxWidth())
        .heightIn(min = (if (small) Dim.btnSmall else if (style == KButtonStyle.Ghost) Dim.tap else Dim.btn).dp)
    val surface =
        when {
            glass && style == KButtonStyle.Primary -> base.glassSurface(shape, fill = K.mint.copy(alpha = .92f), elevation = 10.dp, hairline = Color(0x59FFFFFF))
            glass && style == KButtonStyle.Secondary -> base.glassSurface(shape, fill = Glass.fill, elevation = 5.dp)
            glass && style == KButtonStyle.Danger -> base.glassSurface(shape, fill = K.danger.copy(alpha = .16f), elevation = 5.dp, hairline = K.danger.copy(alpha = .30f))
            else -> base.clip(shape).background(bg).then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
        }
    Row(
        surface.alpha(if (enabled) 1f else .45f).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 16.dp else 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, style = if (small) KType.button.copy(fontSize = 15.sp) else KType.button, color = fg)
    }
}

/** Icon button: 44dp circle; [bordered] adds the surface fill + hairline. */
@Composable
fun KIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    bordered: Boolean = false, tint: Color = K.txt, size: Int = Dim.tap,
) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .then(
                if (bordered && glass) Modifier.glassSurface(CircleShape, fill = Glass.fill, elevation = 5.dp)
                else if (bordered) Modifier.background(K.surf).border(1.dp, K.line, CircleShape)
                else Modifier
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp)) }
}
