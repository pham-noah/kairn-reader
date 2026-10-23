package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.R
import app.kairn.data.Store
import app.kairn.data.WifiTransfer
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Add books: Wi-Fi transfer (L-04) and watched folders (L-01).
 *
 * The Wi-Fi server belongs to this screen: it starts on the button, and DisposableEffect stops it when
 * the screen closes, so nothing keeps listening in the background.
 */
@Composable
fun AddBooksScreen(
    store: Store, onBack: () -> Unit, onPickFolder: () -> Unit, onPickBook: () -> Unit,
    onImportFile: (File, String) -> Unit,
) {
    val toast = LocalToast.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val books by store.books.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf(false) }

    // The server reports from its own thread, so its results are read on a poll rather than written into
    // Compose state off the main thread.
    val received = remember { AtomicInteger(0) }
    val lastReceived = remember { arrayOfNulls<String>(1) }
    val wifi = remember {
        WifiTransfer(File(store.copiesDir, "wifi")) { file, name ->
            received.incrementAndGet()
            lastReceived[0] = name
            onImportFile(file, name)
        }
    }
    var running by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf<String?>(null) }
    var requireCode by remember { mutableStateOf(true) }   // default on: the address alone should not be enough
    var code by remember { mutableStateOf<String?>(null) }
    var receivedCount by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { wifi.stop() } }   // nothing listens once this screen is gone
    LaunchedEffect(running) {
        while (running) { receivedCount = received.get(); last = lastReceived[0]; delay(800) }
    }

    Column(Modifier.fillMaxSize()) {
        KSubHeader(stringResource(R.string.s_add_books), onBack, subtitle = stringResource(R.string.s_everything_here_works_without_internet))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.screenPad.dp).padding(bottom = 24.dp)) {
            KCard(padding = 20) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, null, tint = K.mint, modifier = Modifier.size(22.dp)); Text(stringResource(R.string.s_send_from_a_computer), style = KType.h2)
                }
                if (!running) {
                    Text(stringResource(R.string.s_kairn_shows_an_address_open_it_on_a_comp), style = KType.body.copy(color = K.mut), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.s_require_a_code), style = KType.title)
                            Text(stringResource(R.string.s_only_the_address_kairn_shows_will_work), style = KType.small.copy(color = K.dim))
                        }
                        KSwitch(requireCode, { requireCode = it })
                    }
                    Spacer(Modifier.height(6.dp))
                    KButton(if (failed) stringResource(R.string.s_try_again) else stringResource(R.string.s_start_wi_fi_transfer), {
                        val c = if (requireCode) (1000..9999).random().toString() else null
                        val started = wifi.start(code = c)
                        // A server with no LAN address is unreachable: treat it as a failure, not as a
                        // screen showing a URL nobody can open.
                        code = c
                        address = wifi.url
                        running = started && address != null
                        failed = !running
                        if (!running) wifi.stop()
                    })
                    if (failed) Text(stringResource(R.string.s_no_wi_fi_network_found_join_a_wi_fi_netw), style = KType.small.copy(color = K.amber), modifier = Modifier.padding(top = 10.dp))
                } else {
                    Text(stringResource(R.string.s_open_this_on_your_computer_on_the_same_w), style = KType.body.copy(color = K.mut), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.bg).border(1.dp, K.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(address ?: stringResource(R.string.s_no_network_address), color = K.mint, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    code?.let { Text(stringResource(R.string.code_is_the_address, it), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 8.dp)) }
                    Text(
                        when {
                            receivedCount == 0 -> "Waiting for books. Leave this screen open while you send."
                            receivedCount == 1 -> "1 book received."
                            else -> "$receivedCount books received."
                        },
                        style = KType.small, modifier = Modifier.padding(top = 12.dp),
                    )
                    last?.let { Text(stringResource(R.string.last_file, it), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(16.dp))
                    KButton(stringResource(R.string.s_stop_transfer), { wifi.stop(); running = false; address = null }, style = KButtonStyle.Secondary)
                }
            }
            KGroup("Folders Kairn watches") {
                if (settings.folders.isEmpty()) Text(stringResource(R.string.s_no_folders_yet_new_epub_files_in_a_watch), style = KType.small, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                else KCard {
                    settings.folders.forEachIndexed { i, f ->
                        val n = books.count { it.folder == f.uri }
                        KRow(f.name, subtitle = pluralStringResource(R.plurals.books_count, n, n), leading = Icons.Default.Folder, divider = i > 0) {
                            KSwitch(f.enabled, { v ->
                                store.saveSettings { st -> st.copy(folders = st.folders.map { if (it.uri == f.uri) it.copy(enabled = v) else it }) }
                                toast.show(if (v) "Watching ${f.name}" else "Stopped watching ${f.name}")
                            })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                KButton(stringResource(R.string.s_add_folder_or_files), { chooser = true }, style = KButtonStyle.Secondary)
            }
        }
    }
    if (chooser) KSheet({ chooser = false }, stringResource(R.string.s_add_books)) {
        KAction(stringResource(R.string.add_folder), Icons.Default.Folder, { chooser = false; onPickFolder() }, divider = false)
        KAction(stringResource(R.string.s_pick_epub_files), Icons.Default.Description, { chooser = false; onPickBook() })
    }
}
