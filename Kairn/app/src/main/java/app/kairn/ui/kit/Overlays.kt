package app.kairn.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.ui.theme.A
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import app.kairn.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Glass sheet. The glass is the sheet's own surface: never nest a rounded glass surface inside the
 *  ModalBottomSheet, which already clips it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (glass) Color(0xF21B2024) else K.surf,
        scrimColor = Color.Black.copy(alpha = if (glass) .34f else .55f),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 14.dp).size(40.dp, 4.dp).background(if (glass) Color(0x52FFFFFF) else K.grab, RoundedCornerShape(4.dp))) },
    ) {
        Column(Modifier.padding(horizontal = Dim.screenPad.dp).padding(bottom = 22.dp).navigationBarsPadding().imePadding()) {
            if (title != null) Text(title, style = KType.h2, modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}

/** Action sheet row. */
@Composable
fun KAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, danger: Boolean = false, divider: Boolean = true) {
    Column {
        if (divider) HorizontalDivider(color = K.line)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = if (danger) K.danger else K.mut, modifier = Modifier.size(22.dp))
            Text(label, style = KType.title, color = if (danger) K.danger else K.txt)
        }
    }
}

/** Toast: a light pill near the bottom that fades after a moment. It can carry one action (Undo), and it can
 *  be swiped away — a pill over the page that ignores a swipe reads as a stuck one. */
class ToastState {
    var message by mutableStateOf<String?>(null); private set
    private var actionLabel by mutableStateOf<String?>(null)
    private var pending: (() -> Unit)? = null
    private var stamp by mutableIntStateOf(0)
    private var lifeMs by mutableLongStateOf(SHORT_MS)

    fun show(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        message = text
        this.actionLabel = if (action != null) actionLabel else null
        pending = action
        lifeMs = if (action != null) LONG_MS else SHORT_MS
        stamp++
    }

    /** Gone now: the timer ran out, or a swipe took it. An action nobody can press any more is dropped. */
    fun dismiss() {
        if (message == null) return
        pending = null; actionLabel = null; message = null
    }

    private fun fire() {
        val a = pending
        dismiss()
        a?.invoke()
    }

    @Composable fun Host(modifier: Modifier = Modifier) {
        // Keep the last text: when `message` goes null the exit animation still draws the pill.
        val last = remember { arrayOf("") }
        message?.let { last[0] = it }
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val slop = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
        LaunchedEffect(stamp) { drag.snapTo(0f); if (message != null) { delay(lifeMs); dismiss() } }
        AnimatedVisibility(message != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier.padding(horizontal = 20.dp).shadow(10.dp, RoundedCornerShape(16.dp)).background(K.toastBg, RoundedCornerShape(16.dp))
                    // The swipe follows the finger and, past 80dp, takes the pill with it. It consumes the
                    // drag: the reader pages a chapter on any horizontal flick, and a swipe meant for the
                    // pill must not turn the page under it.
                    .offset { IntOffset(drag.value.roundToInt(), 0) }
                    .graphicsLayer { alpha = 1f - (abs(drag.value) / (slop * 4)).coerceIn(0f, .55f) }
                    .pointerInput(stamp) {
                        detectHorizontalDragGestures(
                            onDragEnd = { if (abs(drag.value) > slop) dismiss() else scope.launch { drag.animateTo(0f) } },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                            onHorizontalDrag = { change, dx -> change.consume(); scope.launch { drag.snapTo(drag.value + dx) } },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(last[0], color = K.toastInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                actionLabel?.let { label ->
                    // 48dp tall, not a strip of 15sp text: the toast's only action has a five-second life.
                    Row(
                        Modifier.heightIn(min = 48.dp).padding(start = 14.dp)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { fire() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = Color(0xFF0B6E55), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    private companion object {
        const val SHORT_MS = 2600L
        const val LONG_MS = 5000L      // an action (Undo) has to stay long enough to be pressed
    }
}
val LocalToast = staticCompositionLocalOf { ToastState() }

/** Small anchored menu with a check on the active item. */
@Composable
fun KMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    DropdownMenu(expanded, onDismiss, containerColor = K.surf2, shape = RoundedCornerShape(16.dp)) {
        items.forEachIndexed { i, (label, active) ->
            DropdownMenuItem(
                text = { Text(label, fontWeight = FontWeight.SemiBold, color = if (active) A.accent else K.txt) },
                trailingIcon = { if (active) Icon(Icons.Default.Check, null, tint = A.accent) },
                onClick = { onPick(i); onDismiss() },
            )
        }
    }
}
