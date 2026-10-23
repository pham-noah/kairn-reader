package app.kairn.ui.kit

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.Glass
import app.kairn.ui.theme.glass
import app.kairn.ui.theme.glassSurface

/** Tabs carry a string RESOURCE, not a literal: the nav bar is the first thing a translation loses. */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    Library(R.string.tab_library, Icons.Default.AutoStories),
    Collections(R.string.tab_collections, Icons.Default.CollectionsBookmark),
    Settings(R.string.tab_settings, Icons.Default.Settings),
}

/** Bottom nav: three tabs, mint pill behind the selected icon. */
@Composable
fun KNavBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    val bar = if (glass)
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp).glassSurface(RoundedCornerShape(999.dp), fill = Glass.fill, elevation = 14.dp)
    else Modifier.background(K.bg)
    Column(if (glass) Modifier.fillMaxWidth() else bar) {
        if (!glass) HorizontalDivider(color = K.line)
        Column(bar) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = if (glass) 6.dp else 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tab.entries.forEach { t ->
                    val on = t == selected
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).selectable(selected = on, role = Role.Tab) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        Box(Modifier.size(56.dp, 30.dp).clip(RoundedCornerShape(999.dp)).background(if (on) (if (glass) Color(0x26FFFFFF) else K.mint.copy(alpha = .14f)) else Color.Transparent), contentAlignment = Alignment.Center) {
                            Icon(t.icon, null, tint = if (on) K.mint else K.dim, modifier = Modifier.size(22.dp))
                        }
                        // Nav label contrast on the glass panel: K.mut is 6.56:1. Nothing smaller or dimmer
                        // goes on the primary navigation.
                        Text(stringResource(t.label), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) K.mint else K.mut)
                    }
                }
            }
        }
    }
}

/** Sub-header: back button + title (+ optional subtitle) for pushed screens. */
@Composable
fun KSubHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Column { Text(title, style = KType.h2); if (subtitle != null) Text(subtitle, style = KType.small) }
    }
}

/** Screen title row used by the three tabs (title left, actions right). */
@Composable
fun KScreenHeader(title: String, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        Text(title, style = KType.h1, modifier = Modifier.weight(1f))
        actions()
    }
}
