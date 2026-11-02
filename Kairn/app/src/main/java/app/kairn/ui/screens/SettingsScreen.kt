package app.kairn.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kairn.R
import app.kairn.data.Ambient
import app.kairn.data.ReaderPrefs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.text.DateFormat
import java.util.Date

// Built once: .copy() at the call sites re-created these on every recomposition (every toggle).
private val SectionTitle = KType.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
private val SmallDim = KType.small.copy(color = K.dim)

/** null is "follow the system". The names are the languages' own names, deliberately not translated. */
private val languageTags: List<String?> = listOf(null, "en", "es", "fi", "ja", "vi", "zh-CN")
@Composable private fun languageLabel(tag: String?): String = when (tag) {
    null, "" -> stringResource(R.string.s_system)
    "es" -> "Español"
    "fi" -> "Suomi"
    "ja" -> "日本語"
    "vi" -> "Tiếng Việt"
    "zh-CN" -> "简体中文"
    else -> "English"
}

/** N-07: one place for appearance, library, data and about. */
@Composable
fun SettingsScreen(
    store: Store, onWatchedFolders: () -> Unit, onBackup: () -> Unit, onScan: () -> Unit,
    onCheckUpdates: () -> Unit = {},
    showUpdates: Boolean = true,
    searchCacheLabel: String = "",
    onClearSearchCache: () -> Unit = {},
) {
    val s by store.settings.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var licences by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var defaultsOpen by remember { mutableStateOf(false) }
    var langOpen by remember { mutableStateOf(false) }
    // Read from AppCompat, not from a file of our own: it is what actually decides the resource locale,
    // and on API 33+ it is backed by the system's per-app language setting.
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { null }
    val stats by store.stats.collectAsStateWithLifecycle()
    val books by store.books.collectAsStateWithLifecycle()
    val finishedCount = remember(books) { books.count { it.status == Status.FINISHED } }
    // One line of proof that the record exists, so the row is worth tapping.
    val statsSub = if (stats.totalMinutes() == 0) stringResource(R.string.s_no_reading_yet)
                   else pluralStringResource(R.plurals.reading_today, stats.minutesToday(), stats.minutesToday(), stats.streak())
    // Hoisted: an inline listOf() is a new instance every recomposition, an unstable argument that blocks
    val themes = remember { listOf(ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.GRAY, ThemeId.DARK, ThemeId.BLACK) }
    val ambients = remember { listOf(Ambient.MINT, Ambient.OCEAN, Ambient.SUNSET, Ambient.PLAIN) }
    val surfaceOpts = listOf(R.string.s_surface_glass, R.string.s_surface_solid)
    // Derived, not a parallel literal list: names and values feed one KSegmented, so they cannot drift
    // out of order.
    val ambientNames = remember(ambients) { ambients.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } }
    val themeNames = remember(themes) { themes.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } }
    val autoOpts = listOf(R.string.s_auto_off, R.string.s_auto_system)
    val swipeNames = listOf(R.string.s_swipe_short, R.string.s_swipe_medium, R.string.s_swipe_long)
    val swipeVals = remember { listOf(28, 48, 80) }
    val speeds = remember { listOf(180, 240, 300, 360) }
    val speedNames = listOf(R.string.s_speed_slow, R.string.s_speed_normal, R.string.s_speed_fast, R.string.s_speed_vfast)
    val lastDate = remember(s.lastBackup) { if (s.lastBackup > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.lastBackup)) else null }
    val last = if (lastDate != null) stringResource(R.string.s_last_backup_on, lastDate) else stringResource(R.string.s_no_backup_yet)
    val folders = s.folders.count { it.enabled }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dim.screenPad.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.tab_settings), style = KType.h1, modifier = Modifier.padding(top = 16.dp))
        KGroup(stringResource(R.string.s_group_appearance)) {
            KCard(padding = 16) {
                Text(stringResource(R.string.s_surface), style = SectionTitle, modifier = Modifier.padding(bottom = 10.dp))
                KSegmented(surfaceOpts.map { stringResource(it) }, if (s.glass) 0 else 1, { i -> store.saveSettings { it.copy(glass = i == 0) } })
                if (s.glass) {
                    Text(stringResource(R.string.s_ambient_colour), style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                    KSegmented(ambientNames, ambients.indexOf(s.ambient), { i -> store.saveSettings { it.copy(ambient = ambients[i]) } })
                }
                Text(stringResource(R.string.s_reading_theme), style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                KSegmented(themeNames, themes.indexOf(s.theme), { i -> store.saveSettings { it.copy(theme = themes[i], defaults = it.defaults.copy(theme = themes[i])) } })
                Text(stringResource(R.string.s_switch_automatically), style = SectionTitle, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                // A "Schedule" mode (light/dark from times) is not built; only Off and System are real.
                KSegmented(autoOpts.map { stringResource(it) }, if (s.autoSwitch) 1 else 0, { i -> store.saveSettings { it.copy(autoSwitch = i == 1) } })
            }
        }
        KGroup(stringResource(R.string.s_group_reading)) {
            KCard {
                KRow(stringResource(R.string.s_keep_screen_on)) { KSwitch(s.keepScreenOn, { v -> store.saveSettings { it.copy(keepScreenOn = v) } }) }
                KRow(stringResource(R.string.s_full_screen), divider = true) { KSwitch(s.fullscreen, { v -> store.saveSettings { it.copy(fullscreen = v) } }) }
                KRow(stringResource(R.string.s_volume_keys_turn_pages), divider = true) { KSwitch(s.volumeKeys, { v -> store.saveSettings { it.copy(volumeKeys = v) } }) }
                // The time left is a guess about how fast YOU read; this is that guess.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.s_reading_speed), style = KType.title)
                    KSegmented(
                        speedNames.map { stringResource(it) },
                        speeds.indexOf(s.readingWpm).coerceAtLeast(0),
                        { i -> store.saveSettings { it.copy(readingWpm = speeds[i]) } },
                        modifier = Modifier.width(240.dp),
                    )
                }
                KRow(stringResource(R.string.s_swipe_to_change_chapter), divider = true) { KSwitch(s.swipeChapters, { v -> store.saveSettings { it.copy(swipeChapters = v) } }) }
                if (s.swipeChapters) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.s_swipe_distance), style = KType.title)
                        KSegmented(
                            swipeNames.map { stringResource(it) },
                            when { s.swipeThreshold <= 34 -> 0; s.swipeThreshold <= 62 -> 1; else -> 2 },
                            { i -> store.saveSettings { it.copy(swipeThreshold = swipeVals[i]) } },
                            modifier = Modifier.width(180.dp),
                        )
                    }
                }
                // The selection menu's two optional actions. Off means the item is not built at all - a
                // disabled row in a text-selection toolbar has nowhere to explain itself.
                KRow(stringResource(R.string.s_share_a_passage), divider = true) {
                    KSwitch(s.shareSelection, { v -> store.saveSettings { it.copy(shareSelection = v) } })
                }
                KRow(stringResource(R.string.s_look_a_word_up), divider = true) {
                    KSwitch(s.lookupSelection, { v -> store.saveSettings { it.copy(lookupSelection = v) } })
                }
                // Type size, spacing and margins are per book, so a reader who needs large type otherwise
                // re-sets them on every new book.
                KRow(stringResource(R.string.s_reading_defaults), divider = true, onClick = { defaultsOpen = true }) { KChevron() }
            }
        }
        KGroup(stringResource(R.string.s_group_library)) {
            KCard {
                KRow(stringResource(R.string.s_find_books_on_this_device)) {
                    KSwitch(s.autoDetect, { v ->
                        store.saveSettings { it.copy(autoDetect = v) }
                        if (v) onScan()
                    })
                }
                KRow(stringResource(R.string.s_watched_folders), subtitle = pluralStringResource(R.plurals.folders_count, folders, folders), divider = true, onClick = onWatchedFolders) { KChevron() }
            }
        }
        KGroup(stringResource(R.string.s_group_your_data)) {
            KCard {
                KRow(stringResource(R.string.s_backup_and_restore), subtitle = last, onClick = onBackup, divider = true) { KChevron() }
                // Safe to delete: the dumps rebuild from the books on the next search.
                if (searchCacheLabel.isNotBlank()) KRow(stringResource(R.string.s_search_cache), subtitle = searchCacheLabel, onClick = onClearSearchCache, divider = false) { KChevron() }
                // DS-02: the reading record lives beside the backup, because that is the group about "your stuff".
                KRow(stringResource(R.string.s_your_reading), subtitle = statsSub, divider = true, onClick = { statsOpen = true }) { KChevron() }
            }
        }
        KGroup(stringResource(R.string.s_group_about)) {
            KCard {
                // In the About card rather than a section of its own: it is one row, and a language is
                // something you set once.
                KRow(stringResource(R.string.s_language), subtitle = languageLabel(currentLang), onClick = { langOpen = true }) { KChevron() }
                // DS-04: the one request Kairn makes on its own, with its own switch next to it. A store
                // build (F-Droid) has no updater at all, so the rows are not shown there.
                if (showUpdates) {
                    KRow(stringResource(R.string.s_check_for_updates), divider = true) {
                        KSwitch(s.checkUpdates, { v -> store.saveSettings { it.copy(checkUpdates = v) } })
                    }
                    KRow(stringResource(R.string.s_check_now), divider = true, onClick = onCheckUpdates) { KChevron() }
                }
                // The credit sits above the licences and the issue tracker: it is who made the app, not a
                // utility. The link opens the browser; Kairn has no web code of its own.
                KRow(stringResource(R.string.s_made_by_noah_pham), subtitle = stringResource(R.string.s_github_com_pham_noah), divider = true, onClick = {
                    openRow(ctx, "https://github.com/pham-noah")
                }) { KChevron() }
                // The source, one row under the credit. Same pattern: the browser opens the page, so Kairn
                // keeps no web code and no network permission of its own for links.
                KRow(stringResource(R.string.s_kairn_on_github), subtitle = stringResource(R.string.s_source_code_issues_and_releases), divider = true, onClick = {
                    openRow(ctx, "https://github.com/pham-noah/kairn-reader")
                }) { KChevron() }
                KRow(stringResource(R.string.s_open_source_licences), divider = true, onClick = { licences = true }) { KChevron() }
                // No network code of our own: the browser opens the tracker.
                KRow(stringResource(R.string.s_report_problem), divider = false, onClick = {
                    openRow(ctx, "https://github.com/pham-noah/kairn-reader/issues")
                }) { KChevron() }
            }
        }
        Text(stringResource(R.string.s_footer_tagline), style = KType.small.copy(color = K.dim, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 6.dp, end = 6.dp))
    }

    if (statsOpen) StatsSheet(stats, finishedCount) { statsOpen = false }
    if (licences) KSheet({ licences = false }, stringResource(R.string.s_open_source_licences)) {
        KCard {
            KRow(stringResource(R.string.s_readium_kotlin_toolkit), subtitle = stringResource(R.string.s_bsd_3_clause_renders_your_books))
            KRow(stringResource(R.string.s_manrope_and_literata), subtitle = stringResource(R.string.s_sil_open_font_license_bundled_with_the_a), divider = true)
            KRow(stringResource(R.string.s_libron), subtitle = stringResource(R.string.s_libron_licence))
        }
    }
    if (defaultsOpen) KSheet({ defaultsOpen = false }, stringResource(R.string.s_reading_defaults)) {
        ReadingDefaults(s.defaults) { prefs -> store.saveSettings { it.copy(defaults = prefs) } }
    }
    if (langOpen) KSheet({ langOpen = false }, stringResource(R.string.s_language)) {
        KCard {
            languageTags.forEachIndexed { i, tag ->
                KRow(languageLabel(tag), divider = i > 0, onClick = {
                    // AppCompat recreates the activities itself; the framework takes over on API 33+.
                    AppCompatDelegate.setApplicationLocales(
                        if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                    )
                    langOpen = false
                }) { if (tag == currentLang) Text("\u2713", style = KType.title.copy(color = K.mint)) }
            }
        }
    }
}

/** The app-wide reading defaults: what a book with no prefs of its own opens with. */
@Composable
internal fun ReadingDefaults(defaults: ReaderPrefs, onChange: (ReaderPrefs) -> Unit) =
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        KControl(stringResource(R.string.s_text_size), stringResource(R.string.value_percent, (defaults.fontScale * 100).toInt())) {
            KSlider(defaults.fontScale.toFloat(), { v -> onChange(defaults.copy(fontScale = v.toDouble())) }, 0.7f..2.5f)
        }
        KControl(stringResource(R.string.s_line_spacing), stringResource(R.string.s_2f).format(defaults.lineHeight)) {
            KSlider(defaults.lineHeight.toFloat(), { v -> onChange(defaults.copy(lineHeight = v.toDouble())) }, 1.0f..2.2f)
        }
        KControl(stringResource(R.string.s_paragraph_spacing), stringResource(R.string.s_1f).format(defaults.paragraphSpacing)) {
            KSlider(defaults.paragraphSpacing.toFloat(), { v -> onChange(defaults.copy(paragraphSpacing = v.toDouble())) }, 0f..2f)
        }
        KControl(stringResource(R.string.s_margins), stringResource(R.string.s_1f).format(defaults.margins)) {
            KSlider(defaults.margins.toFloat(), { v -> onChange(defaults.copy(margins = v.toDouble())) }, 0.5f..2.5f)
        }
        KCard {
            KRow(stringResource(R.string.s_justify_text)) { KSwitch(defaults.justify, { onChange(defaults.copy(justify = it)) }) }
            KRow(stringResource(R.string.s_hyphenation), divider = true) { KSwitch(defaults.hyphens, { onChange(defaults.copy(hyphens = it)) }) }
        }
        Text(stringResource(R.string.s_new_books_open_with_these), style = SmallDim, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    }

/** Opens a link; when no browser handles it, the tap says so instead of doing nothing. */
private fun openRow(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        android.widget.Toast.makeText(ctx, app.kairn.R.string.s_no_browser, android.widget.Toast.LENGTH_SHORT).show()
    }
}
