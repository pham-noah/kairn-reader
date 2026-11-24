package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.ui.kit.*
import app.kairn.ui.theme.A
import app.kairn.ui.theme.*

/** N-06: three short pages (promise, ways to add books, pick a look). */
@Composable
fun OnboardingScreen(store: Store, onFinish: (goToAdd: Boolean) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val current by store.settings.collectAsState()

    Column(Modifier.fillMaxSize().background(K.bg).statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 28.dp)) {
        // Skip lives up here with the page dots. Under the primary button it was a grey word 60dp from a
        // thumb already on "Next", for an action that undoes the flow.
        Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i -> Box(Modifier.height(8.dp).width(if (i == step) 26.dp else 8.dp).clip(RoundedCornerShape(8.dp)).background(if (i == step) A.accent else Color(0xFF313940))) ; Spacer(Modifier.width(8.dp)) }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(if (step < 2) R.string.s_skip else R.string.s_skip_for_now),
                style = KType.small.copy(color = K.dim),
                modifier = Modifier.clickable { onFinish(false) }.padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        // Centred in the space the footer leaves: top-aligned, the three bullets ended 180dp above the button
        // and the page read as a document that had run out rather than a page that had been composed.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.verticalScroll(rememberScrollState())) {   // scrolls at large font sizes instead of clipping
            when (step) {
                0 -> {
                    CairnMark(size = 64)
                    Text(stringResource(R.string.s_onboarding_promise), style = KType.onboardingTitle, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp))
                    Text(stringResource(R.string.s_a_quiet_epub_reader_your_books_notes_and), style = KType.body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = K.mut), modifier = Modifier.widthIn(max = 300.dp))
                    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(R.string.s_bullet_no_account, R.string.s_bullet_airplane, R.string.s_bullet_free).forEach {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Check, null, tint = A.accent, modifier = Modifier.size(20.dp)); Text(stringResource(it), fontWeight = FontWeight.SemiBold, color = K.txt)
                            }
                        }
                    }
                }
                1 -> {
                    Text(stringResource(R.string.s_bring_your_books), style = KType.onboardingTitle, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp))
                    Text(stringResource(R.string.s_three_ways_to_add_epub_files_you_can_use), style = KType.body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = K.mut), modifier = Modifier.widthIn(max = 300.dp))
                    Column(Modifier.padding(top = 14.dp)) {
                        Option(Icons.Default.Folder, stringResource(R.string.add_folder), stringResource(R.string.s_new_books_show_up_on_their_own))
                        Option(Icons.Default.Wifi, stringResource(R.string.s_send_over_wi_fi), stringResource(R.string.s_from_a_computer_no_cable))
                        Option(Icons.Default.Description, stringResource(R.string.s_open_a_file), stringResource(R.string.s_tap_an_epub_in_any_app))
                    }
                }
                else -> {
                    Text(stringResource(R.string.s_pick_your_look), style = KType.onboardingTitle, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp))
                    Text(stringResource(R.string.s_dark_is_the_default_you_can_change_it_in), style = KType.body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = K.mut), modifier = Modifier.widthIn(max = 300.dp))
                    Row(Modifier.padding(top = 26.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // Every theme the reader offers, in the same page-tone order as the Display sheet:
                        // a look that is missing here reads as a look that does not exist.
                        listOf(ThemeId.LIGHT, ThemeId.SEPIA, ThemeId.GRAY, ThemeId.DARK, ThemeId.BLACK).forEach { t ->
                            KThemeSwatch(t, current.theme == t, { store.saveSettings { it.copy(theme = t, defaults = it.defaults.copy(theme = t)) } })
                        }
                    }
                    val ui = readerUi(current.theme)
                    Text(stringResource(R.string.s_call_me_ishmael_some_years_ago_never_min), fontFamily = KFonts.serif, fontSize = 17.sp, lineHeight = 27.sp, color = ui.text,
                        modifier = Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(Dim.card.dp)).background(ui.page).border(1.dp, K.line, RoundedCornerShape(Dim.card.dp)).padding(18.dp))
                }
            }
        }
        }   // Box
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton(if (step < 2) stringResource(R.string.s_next) else stringResource(R.string.s_add_your_first_books), { if (step < 2) step++ else onFinish(true) })
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, desc: String) {
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape).background(K.surf).border(1.dp, K.line, shape).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(A.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = A.accent, modifier = Modifier.size(22.dp)) }
        Column { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = K.txt); Text(desc, style = KType.small) }
    }
}
