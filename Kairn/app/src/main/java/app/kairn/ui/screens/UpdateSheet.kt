package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kairn.R
import app.kairn.data.Update
import app.kairn.ui.NoteBlocks
import app.kairn.ui.noteBlocks
import app.kairn.ui.kit.KButton
import app.kairn.ui.NoteBlocks
import app.kairn.ui.noteBlocks
import app.kairn.ui.kit.KButtonStyle
import app.kairn.ui.kit.KSheet
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.io.File

/**
 * The state of one update check, held by the shell so the same sheet serves the automatic check and the
 * "Check for updates" row in Settings.
 */
@Stable
class UpdateUi {
    var open by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var release by mutableStateOf<Update.Release?>(null)
    var message by mutableStateOf<String?>(null)      // "up to date" or why the check failed
    var downloading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var apk by mutableStateOf<File?>(null)
}

/** How tall the release body may get before it scrolls: past this the buttons stop moving. */
private val NOTES_MAX = 260.dp

/** The one slot the download button, the progress row and the install button take turns in. */
private val ACTION_MIN = 68.dp

/** DS-04: what the check found, and the two taps that follow it - download, then the system installer. */
@Composable
fun UpdateSheet(
    ui: UpdateUi,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onClose: () -> Unit,
) {
    KSheet(onClose, stringResource(R.string.s_software_update)) {
        when {
            ui.checking -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = K.mint, strokeWidth = 2.dp)
                Text(stringResource(R.string.s_checking_for_updates), style = KType.body, modifier = Modifier.padding(start = 12.dp))
            }
            ui.release != null -> {
                val r = ui.release!!
                Text(stringResource(R.string.s_version_available, r.version), style = KType.h2)
                if (r.notes.isNotBlank()) {
                    // DS-04: the body is Markdown from the release page, drawn as the app's own type, in a
                    // bounded scroll area: a long body cannot push the buttons off the sheet.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                            .heightIn(max = NOTES_MAX).verticalScroll(rememberScrollState()),
                    ) { NoteBlocks(noteBlocks(r.notes)) }
                }
                // The action slot holds its height from "Download" to "Downloading": the sheet used to shrink
                // the moment the button was replaced by the progress row, and the reader had to drag it back.
                Box(Modifier.fillMaxWidth().heightIn(min = ACTION_MIN).padding(top = 12.dp)) {
                    when {
                        ui.downloading -> Column {
                            Text(stringResource(R.string.s_downloading, (ui.progress * 100).toInt()), style = KType.small)
                            // Drawn rather than Material's indicator: a 6dp track in the app's own mint, and one
                            // API that exists in every Material3 version this app builds against.
                            Box(
                                Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(K.line),
                            ) {
                                Box(Modifier.fillMaxWidth(ui.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.mint))
                            }
                        }
                        ui.apk != null -> Column {
                            KButton(stringResource(R.string.s_install_update), onInstall)
                            // The refusal has one cause and one cure, once.
                            Text(
                                stringResource(R.string.s_install_refused_hint),
                                style = KType.small.copy(color = K.dim),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        else -> KButton(stringResource(R.string.s_download), onDownload)
                    }
                }
                if (r.size > 0) Text(
                    stringResource(R.string.s_update_size, r.size / 1024 / 1024),
                    style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 10.dp),
                )
            }
            else -> {
                Text(ui.message ?: stringResource(R.string.s_up_to_date), style = KType.body.copy(color = K.mut))
                KButton(stringResource(R.string.s_check_again), onCheck, Modifier.padding(top = 16.dp), style = KButtonStyle.Secondary)
            }
        }
        Text(
            stringResource(R.string.s_update_source_note),
            style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 16.dp),
        )
    }
}
