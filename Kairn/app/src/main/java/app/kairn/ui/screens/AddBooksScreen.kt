package app.kairn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.data.Store
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType

/** Add books: Wi-Fi transfer card (L-04, not built yet) and watched folders (L-01). */
@Composable
fun AddBooksScreen(store: Store, onBack: () -> Unit, onPickFolder: () -> Unit, onPickBook: () -> Unit) {
    val toast = LocalToast.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val books by store.books.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        KSubHeader("Add books", onBack, subtitle = "Everything here works without internet.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.screenPad.dp).padding(bottom = 24.dp)) {
            KCard(padding = 20) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, null, tint = K.mint, modifier = Modifier.size(22.dp)); Text("Send from a computer", style = KType.h2)
                }
                Text("Kairn shows an address. Open it on a computer on the same Wi-Fi and drop your EPUB files on the page.", style = KType.body.copy(color = K.mut), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                // L-04 is not built yet; the live state (address, code, received list) belongs here once it is.
                KButton("Start Wi-Fi transfer", { toast.show("Wi-Fi transfer is not built yet") })
            }
            KGroup("Folders Kairn watches") {
                if (settings.folders.isEmpty()) Text("No folders yet. New EPUB files in a watched folder appear on their own.", style = KType.small, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                else KCard {
                    settings.folders.forEachIndexed { i, f ->
                        val n = books.count { it.folder == f.uri }
                        KRow(f.name, subtitle = "$n ${if (n == 1) "book" else "books"}", leading = Icons.Default.Folder, divider = i > 0) {
                            KSwitch(f.enabled, { v ->
                                store.saveSettings { st -> st.copy(folders = st.folders.map { if (it.uri == f.uri) it.copy(enabled = v) else it }) }
                                toast.show(if (v) "Watching ${f.name}" else "Stopped watching ${f.name}")
                            })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                KButton("Add folder or files", { chooser = true }, style = KButtonStyle.Secondary)
            }
        }
    }
    if (chooser) KSheet({ chooser = false }, "Add books") {
        KAction("Watch a folder", Icons.Default.Folder, { chooser = false; onPickFolder() }, divider = false)
        KAction("Pick EPUB files", Icons.Default.Description, { chooser = false; onPickBook() })
    }
}
