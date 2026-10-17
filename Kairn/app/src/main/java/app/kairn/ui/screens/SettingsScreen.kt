package app.kairn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kairn.data.Ambient
import app.kairn.data.Library
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.text.DateFormat
import java.util.Date

// Built once; these were re-created with .copy() several times on every recomposition (every toggle).
private val SectionTitle = KType.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
private val SmallDim = KType.small.copy(color = K.dim)

/** N-07: one place for appearance, library, data and about. */
@Composable
fun SettingsScreen(store: Store, library: Library, onWatchedFolders: () -> Unit, onBackup: () -> Unit, onScan: () -> Unit) {
    val s by store.settings.collectAsStateWithLifecycle()
    var licences by remember { mutableStateOf(false) }
    // Hoisted: an inline listOf() at a call site is a NEW list instance every recomposition,
    // which Compose sees as an unstable argument and which blocks skipping. Constant lists live here.
    val themes = remember { listOf(ThemeId.DARK, ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.BLACK) }
    val ambients = remember { listOf(Ambient.MINT, Ambient.OCEAN, Ambient.SUNSET, Ambient.PLAIN) }
    val surfaceOpts = remember { listOf("Glass", "Solid") }
    // Derived, not a parallel hand-written list: themeNames and ambients are fed into one KSegmented as
    // label + value, so reordering one of two literal lists silently labelled the wrong option. Same order,
    // same words, no way to drift.
    val ambientNames = remember(ambients) { ambients.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } }
    val themeNames = remember(themes) { themes.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } }
    val autoOpts = remember { listOf("Off", "System") }
    val swipeNames = remember { listOf("Short", "Medium", "Long") }
    val swipeVals = remember { listOf(28, 48, 80) }
    val last = remember(s.lastBackup) { if (s.lastBackup > 0) "Last backup " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.lastBackup)) else "No backup yet" }
    val folders = s.folders.count { it.enabled }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dim.screenPad.dp).padding(bottom = 24.dp)) {
        Text("Settings", style = KType.h1, modifier = Modifier.padding(top = 16.dp))
        KGroup("Appearance") {
            KCard(padding = 16) {
                Text("Surface", style = SectionTitle, modifier = Modifier.padding(bottom = 10.dp))
                KSegmented(surfaceOpts, if (s.glass) 0 else 1, { i -> store.saveSettings { it.copy(glass = i == 0) } })
                Text(if (s.glass) "Translucent panels with a light sheen. The default." else "Flat, opaque panels (the earlier look).", style = SmallDim, modifier = Modifier.padding(top = 12.dp))
                if (s.glass) {
                    Text("Ambient colour", style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                    KSegmented(ambientNames, ambients.indexOf(s.ambient), { i -> store.saveSettings { it.copy(ambient = ambients[i]) } })
                    Text("The soft colour glow behind the panels.", style = SmallDim, modifier = Modifier.padding(top = 12.dp))
                }
                Text("Reading theme", style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                KSegmented(themeNames, themes.indexOf(s.theme), { i -> store.saveSettings { it.copy(theme = themes[i], defaults = it.defaults.copy(theme = themes[i])) } })
                Text("Switch automatically", style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                // Round 4 also has "Schedule" (light/dark from times). Not built; only Off and System are real.
                KSegmented(autoOpts, if (s.autoSwitch) 1 else 0, { i -> store.saveSettings { it.copy(autoSwitch = i == 1) } })
                Text(if (s.autoSwitch) "Follows your phone's light and dark setting." else "Kairn starts in Dark.", style = SmallDim, modifier = Modifier.padding(top = 12.dp))
            }
        }
        KGroup("Reading") {
            KCard {
                KRow("Keep screen on", subtitle = "While a book is open") { KSwitch(s.keepScreenOn, { v -> store.saveSettings { it.copy(keepScreenOn = v) } }) }
                KRow("Full screen", subtitle = "Hide the status bar when the reader bars are hidden", divider = true) { KSwitch(s.fullscreen, { v -> store.saveSettings { it.copy(fullscreen = v) } }) }
                KRow("Volume keys turn pages", divider = true) { KSwitch(s.volumeKeys, { v -> store.saveSettings { it.copy(volumeKeys = v) } }) }
                KRow("Swipe to change chapter", subtitle = "In scroll mode: left for next, right for previous", divider = true) { KSwitch(s.swipeChapters, { v -> store.saveSettings { it.copy(swipeChapters = v) } }) }
                if (s.swipeChapters) {
                    // One compact row: label left, choice right. No subtitle, no stacked layout.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Swipe distance", style = KType.title)
                        KSegmented(
                            swipeNames,
                            when { s.swipeThreshold <= 34 -> 0; s.swipeThreshold <= 62 -> 1; else -> 2 },
                            { i -> store.saveSettings { it.copy(swipeThreshold = swipeVals[i]) } },
                            modifier = Modifier.width(180.dp),
                        )
                    }
                }
            }
        }
        KGroup("Library") {
            KCard {
                KRow("Find books on this device", subtitle = "Scan for EPUBs already on your phone, like Lithium") {
                    KSwitch(s.autoDetect, { v ->
                        store.saveSettings { it.copy(autoDetect = v) }
                        if (v) { onScan(); if (library.needsAllFilesAccess()) library.requestAllFilesAccess() }
                    })
                }
                KRow("Watched folders", subtitle = "$folders ${if (folders == 1) "folder" else "folders"}, new books appear on their own", divider = true, onClick = onWatchedFolders) { KChevron() }
            }
        }
        KGroup("Your data") {
            KCard { KRow("Backup and restore", subtitle = last, onClick = onBackup) { KChevron() } }
        }
        KGroup("About") {
            KCard {
                KRow("Language") { Text("English", color = K.mut) }
                KRow("Open-source licences", divider = true, onClick = { licences = true }) { KChevron() }
            }
        }
        Text("Kairn, draft build. No account, no ads, no tracking.", style = KType.small.copy(color = K.dim, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 6.dp, end = 6.dp))
    }

    if (licences) KSheet({ licences = false }, "Open-source licences") {
        KCard {
            KRow("Readium Kotlin Toolkit", subtitle = "BSD-3-Clause. Renders your books.")
            KRow("Manrope and Literata", subtitle = "SIL Open Font License. Not bundled yet.", divider = true)
        }
        Text("Dictionary and font licences are still being checked.", style = SmallDim, modifier = Modifier.padding(top = 12.dp))
    }
}
