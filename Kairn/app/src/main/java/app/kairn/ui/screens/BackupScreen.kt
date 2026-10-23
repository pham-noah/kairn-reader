package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.R
import app.kairn.data.Store
import app.kairn.ui.kit.*
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.text.DateFormat
import java.util.Date

enum class BackupPhase { Idle, Running, Done, Failed }

/** Hoisted state so the activity-result callbacks in the shell can drive the screen. */
class BackupUi {
    var phase by mutableStateOf(BackupPhase.Idle)
    var progress by mutableFloatStateOf(0f)
    var fileName by mutableStateOf("")
    var detail by mutableStateOf("")
    var includeFiles by mutableStateOf(false)
}

/** DS-01: Backup and restore (idle, running, done). */
@Composable
fun BackupScreen(ui: BackupUi, store: Store, onBack: () -> Unit, onBackupNow: () -> Unit, onRestoreFromFile: () -> Unit) {
    val s by store.settings.collectAsStateWithLifecycle()
    val last = remember(s.lastBackup) { if (s.lastBackup > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastBackup)) else "Never" }
    Column(Modifier.fillMaxSize()) {
        KSubHeader(stringResource(R.string.s_backup_and_restore), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.screenPad.dp)) {
            Text(stringResource(R.string.s_one_file_holds_your_library_notes_highli), style = KType.body.copy(color = K.mut), modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 18.dp))
            KCard(padding = 16) { Text(stringResource(R.string.s_last_backup), style = KType.small); Text(last, style = KType.h2, modifier = Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.height(12.dp))
            KCard { KRow(stringResource(R.string.s_include_book_files), subtitle = stringResource(R.string.s_makes_the_backup_larger)) { KSwitch(ui.includeFiles, { ui.includeFiles = it }) } }
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (ui.phase) {
                    BackupPhase.Idle, BackupPhase.Failed -> {
                        if (ui.phase == BackupPhase.Failed) KCard(padding = 16) { Text(stringResource(R.string.s_backup_failed), fontWeight = FontWeight.Bold, color = K.danger); Text(ui.detail + stringResource(R.string.s_your_data_is_unchanged), style = KType.small) }
                        KButton(stringResource(R.string.s_back_up_now), onBackupNow); KButton(stringResource(R.string.s_restore_from_a_file), onRestoreFromFile, style = KButtonStyle.Secondary)
                    }
                    BackupPhase.Running -> KCard(padding = 16) {
                        Text(stringResource(R.string.s_backing_up), fontWeight = FontWeight.Bold, color = K.txt)
                        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B3238))) { Box(Modifier.fillMaxWidth(ui.progress).fillMaxHeight().background(K.mint)) }
                        Text(stringResource(R.string.s_your_books_stay_usable_while_this_runs), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 8.dp))
                    }
                    BackupPhase.Done -> {
                        KCard(padding = 16) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Check, null, tint = K.mint, modifier = Modifier.size(24.dp))
                                Column { Text(stringResource(R.string.s_backup_saved), fontWeight = FontWeight.Bold, color = K.txt); Text(ui.fileName, style = KType.small, modifier = Modifier.padding(top = 2.dp)); Text(ui.detail, style = KType.small.copy(color = K.dim)) }
                            }
                        }
                        KButton(stringResource(R.string.s_done), { ui.phase = BackupPhase.Idle }, style = KButtonStyle.Secondary)
                    }
                }
            }
        }
    }
}

/** Restore sheet: merge or replace, with the safety-copy promise (DS-01). */
@Composable
fun RestoreSheet(fileName: String, onDismiss: () -> Unit, onRestore: (replace: Boolean) -> Unit) {
    var replace by remember { mutableStateOf(false) }
    KSheet(onDismiss, stringResource(R.string.s_restore_from_a_backup)) {
        KCard(padding = 16) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = K.mut, modifier = Modifier.size(24.dp)); Text(fileName, fontWeight = FontWeight.Bold, color = K.txt)
            }
        }
        Spacer(Modifier.height(8.dp))
        KRadioRow(stringResource(R.string.s_merge_with_what_is_here), stringResource(R.string.s_keeps_both_if_a_book_is_in_both_the_late), !replace, { replace = false })
        KRadioRow(stringResource(R.string.s_replace_everything), stringResource(R.string.s_makes_this_phone_match_the_backup), replace, { replace = true })
        Text(stringResource(R.string.s_kairn_saves_a_safety_copy_of_your_curren), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp))
        KButton(stringResource(R.string.s_restore), { onRestore(replace) })
    }
}
