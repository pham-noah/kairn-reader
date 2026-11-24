package app.kairn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairn.R
import app.kairn.data.ReadingStats
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import app.kairn.ui.kit.KChip
import app.kairn.ui.kit.KSheet
import app.kairn.ui.kit.insetWell
import app.kairn.ui.theme.A
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * DS-02: the reading record. Three numbers and a week of bars, all from the local stats.json.
 */
@Composable
fun StatsSheet(stats: ReadingStats, finishedBooks: Int, goal: Int = 0, onGoal: (Int) -> Unit = {}, onClose: () -> Unit) {
    KSheet(onClose, stringResource(R.string.s_your_reading)) { StatsContent(stats, finishedBooks, goal, onGoal) }
}

/**
 * The record itself, without the sheet around it: a `KSheet` is a `ModalBottomSheet`, which is a Dialog, so a
 * body composed in it never lays out under Robolectric. This is the composable the JVM test draws.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsContent(stats: ReadingStats, finishedBooks: Int, goal: Int = 0, onGoal: (Int) -> Unit = {}) {
    val week = remember(stats) { stats.recent(7) }
    val best = remember(week) { week.maxOfOrNull { it.second } ?: 0 }
    val weekTotal = remember(week) { week.sumOf { it.second } }
    val streak = remember(stats) { stats.streak() }
    val total = remember(stats) { stats.totalMinutes() }
    val today = remember(stats) { stats.minutesToday() }

    Column {
        StatRow(
            listOf(
                stringResource(R.string.s_today) to minutes(today),
                stringResource(R.string.s_this_week) to minutes(weekTotal),
                stringResource(R.string.s_streak) to stringResource(R.string.s_days_value, streak),
            ),
        )
        // The goal lives where the number it judges is: a target set in Settings would be a number nobody sees
        // while looking at their own record.
        if (goal > 0) {
            val met = today >= goal
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (met) stringResource(R.string.s_goal_met) else stringResource(R.string.s_goal_progress, today, goal),
                    style = KType.small.copy(color = if (met) A.accent else K.mut),
                )
                // "47 of 30 min" reads like arithmetic nobody asked for: once the goal is behind you, the number
                // stands on its own.
                if (met) Text(stringResource(R.string.s_min_value, today), style = KType.small.copy(color = K.dim))
            }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(K.surf2)) {
                Box(Modifier.fillMaxWidth((today.toFloat() / goal).coerceIn(0f, 1f)).fillMaxHeight().background(A.accent))
            }
        }
        Text(
            stringResource(R.string.s_daily_goal),
            style = KType.small.copy(color = K.mut, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // onClick by name: a trailing lambda binds to the last parameter, which here is a count.
            KChip(stringResource(R.string.s_auto_off), goal == 0, onClick = { onGoal(0) })
            listOf(15, 30, 45, 60).forEach { m ->
                // Bare minutes: the unit is in the line above the bar ("24 of 30 min today"), and five chips
                // that each end in "min" wrap a lone "60" onto a row of its own.
                KChip(m.toString(), goal == m, onClick = { onGoal(m) })
            }
        }
        // A chart of seven empty days is decoration, not a record: with nothing read this week the sheet says so
        // in words and keeps seven empty columns out of it.
        if (weekTotal > 0) {
            WeekChart(week, best, Modifier.padding(top = 14.dp))
        } else {
            Text(
                stringResource(R.string.s_no_reading_yet),
                style = KType.small.copy(color = K.dim),
                modifier = Modifier.padding(top = 16.dp, start = 4.dp),
            )
        }
        Text(
            pluralStringResource(R.plurals.reading_total, finishedBooks, total / 60, total % 60, finishedBooks),
            style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
        Text(
            stringResource(R.string.s_stats_local_note),
            style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 8.dp),
        )
    }
}

/**
 * Seven days, oldest first, tallest bar as the reference. Today's bar is the only full-strength one, so the eye
 * lands on it, and the best day carries its own count because that is the number the chart is drawing.
 */
@Composable
private fun WeekChart(week: List<Pair<LocalDate, Int>>, best: Int, modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    Row(
        modifier.fillMaxWidth().insetWell(RoundedCornerShape(18.dp)).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        week.forEach { (day, mins) ->
            val h = (mins.toFloat() / best * 54f).toInt().coerceAtLeast(if (mins > 0) 5 else 0)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // Reserved even on days without the count, so every bar sits on one baseline.
                Text(
                    if (mins > 0 && mins == best) minutes(mins) else "",
                    style = KType.small.copy(color = K.dim, fontSize = 10.sp),
                    maxLines = 1, modifier = Modifier.padding(top = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth(0.66f).height(h.dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(
                                when {
                                    mins <= 0 -> K.line.copy(alpha = 0.55f)
                                    day == today -> A.accent
                                    else -> A.accent.copy(alpha = 0.42f)
                                },
                            ),
                    )
                }
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = KType.small.copy(color = if (day == today) K.txt else K.dim, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 7.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The three stat tiles, all set at ONE size: the size at which the widest value still fits, since three numbers at
 * three sizes look like a mistake. "2 h 41 min" is wider than a third of the sheet on a 360dp phone, and a
 * translated unit can be wider still - shrinking beats clipping.
 */
@Composable
private fun StatRow(cells: List<Pair<String, String>>) {
    val base = remember { KType.body.copy(fontWeight = FontWeight.ExtraBold, color = K.txt) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        // Tile width = a third of the row, less its two gaps; the number gets 88% of what the tile keeps inside its
        // own padding, because a value touching both edges reads as clipped even when it is not.
        val room = with(density) { (((maxWidth - 16.dp) / 3) - 12.dp).toPx() } * 0.88f
        val size = remember(cells, room) {
            var s = 21f
            while (s > 13f && cells.any { measurer.measure(it.second, base.copy(fontSize = s.sp), maxLines = 1).size.width > room }) {
                s -= 0.5f
            }
            s
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEach { (label, value) ->
                StatTile(label, value, size.sp, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, size: TextUnit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().insetWell(RoundedCornerShape(18.dp)).padding(vertical = 13.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = KType.body.copy(fontSize = size, fontWeight = FontWeight.ExtraBold, color = K.txt),
            maxLines = 1,
        )
        Text(
            label,
            style = KType.small.copy(color = K.dim, fontSize = 11.sp),
            maxLines = 1, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Under an hour reads as minutes; above it, hours and the leftover minutes. */
@Composable
private fun minutes(m: Int): String =
    if (m < 60) stringResource(R.string.s_minutes_value, m) else stringResource(R.string.s_hours_value, m / 60, m % 60)
