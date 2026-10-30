package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.data.ReadingStats
import app.kairn.ui.kit.KSheet
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.time.format.TextStyle
import java.util.Locale

/**
 * DS-02: the reading record. Three numbers and a week of bars - enough to see a habit, not enough to be a
 * dashboard. Everything here comes from one local JSON file (stats.json), and nothing leaves the phone.
 */
@Composable
fun StatsSheet(stats: ReadingStats, finishedBooks: Int, onClose: () -> Unit) {
    val week = remember(stats) { stats.recent(7) }
    val best = remember(week) { week.maxOfOrNull { it.second } ?: 0 }
    val weekTotal = remember(week) { week.sumOf { it.second } }
    val streak = remember(stats) { stats.streak() }
    val total = remember(stats) { stats.totalMinutes() }
    val today = remember(stats) { stats.minutesToday() }

    KSheet(onClose, stringResource(R.string.s_your_reading)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox(stringResource(R.string.s_today), minutes(today), Modifier.weight(1f))
            StatBox(stringResource(R.string.s_this_week), minutes(weekTotal), Modifier.weight(1f))
            StatBox(stringResource(R.string.s_streak), stringResource(R.string.s_days_value, streak), Modifier.weight(1f))
        }
        // The week, oldest first, with the tallest bar as the reference: a bar chart that rescales to
        // nothing when every day is empty is worse than no chart.
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            week.forEach { (day, mins) ->
                val h = if (best <= 0) 0 else (mins.toFloat() / best * 64f).toInt().coerceAtLeast(if (mins > 0) 4 else 0)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(66.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth(0.62f).height(h.dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(if (mins > 0) K.mint else K.line),
                        )
                    }
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = KType.small.copy(color = if (mins > 0) K.mut else K.dim, fontSize = 12.sp),
                        modifier = Modifier.padding(top = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            pluralStringResource(R.plurals.reading_total, finishedBooks, total / 60, total % 60, finishedBooks),
            style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 18.dp, start = 4.dp),
        )
        Text(
            stringResource(R.string.s_stats_local_note),
            style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) =
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(K.bg).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = K.txt)
        Text(label, style = KType.small.copy(color = K.dim, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
    }

/** Under an hour reads as minutes; above it, hours and the leftover minutes. */
@Composable
private fun minutes(m: Int): String =
    if (m < 60) stringResource(R.string.s_minutes_value, m) else stringResource(R.string.s_hours_value, m / 60, m % 60)
