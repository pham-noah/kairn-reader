package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import app.kairn.ui.kit.KButton
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
                if (r.notes.isNotBlank()) Text(
                    r.notes.trim().take(500), style = KType.small.copy(color = K.mut),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                when {
                    ui.downloading -> Column(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.s_downloading, (ui.progress * 100).toInt()), style = KType.small)
                        // Drawn rather than Material's indicator: a 6dp track in the app's own mint, and one API
                        // that exists in every Material3 version this app builds against.
                        Box(
                            Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(6.dp)).background(K.line),
                        ) {
                            Box(Modifier.fillMaxWidth(ui.progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.mint))
                        }
                    }
                    ui.apk != null -> {
                        KButton(stringResource(R.string.s_install_update), onInstall, Modifier.padding(top = 16.dp))
                        // The refusal has one cause and one cure, once.
                        Text(
                            stringResource(R.string.s_install_refused_hint),
                            style = KType.small.copy(color = K.dim),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    else -> KButton(stringResource(R.string.s_download), onDownload, Modifier.padding(top = 16.dp))
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
