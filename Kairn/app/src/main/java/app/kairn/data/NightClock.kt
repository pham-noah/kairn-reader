package app.kairn.data

import java.time.LocalTime

/**
 * When the reading theme follows a clock rather than the system.
 *
 * The system's day/night flag is whatever the phone says at launch; a reader who wants their pages light until
 * they go to bed wants the app to decide, not the phone's sunset estimate. These are pure functions so the
 * window arithmetic - which is the only part that can go wrong - is tested on the JVM instead of by waiting
 * until 21:00 and looking at a phone.
 */

/** Minutes after midnight, clamped: a stored value from a broken file must not throw here. */
fun minutesOfDay(hour: Int, minute: Int): Int = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)

/** "21:00", for a label. 24-hour on purpose: it is a time of day, not a clock face. */
fun formatMinutes(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

/**
 * Is [minutes] inside the night window [from] until [to]?
 *
 * A window that runs past midnight ("21:00 to 08:00") is the normal case and the reason this is not a simple
 * `in from..to`. A window with the same start and end would be either never or always; nobody means "never",
 * so it reads as always.
 */
fun isNightAt(minutes: Int, from: Int, to: Int): Boolean {
    val m = ((minutes % 1440) + 1440) % 1440
    val f = ((from % 1440) + 1440) % 1440
    val t = ((to % 1440) + 1440) % 1440
    return when {
        f == t -> true
        f < t -> m >= f && m < t
        else -> m >= f || m < t
    }
}

/** The same question, for right now. */
fun isNightNow(now: LocalTime = LocalTime.now(), from: Int, to: Int): Boolean =
    isNightAt(now.hour * 60 + now.minute, from, to)

/** True when the app's own clock decides night instead of the phone's. */
fun usesSchedule(settings: AppSettings): Boolean = settings.autoSwitch && settings.autoMode == AUTO_SCHEDULE

/** Night, as the app should read it: the clock when the schedule is on, the system otherwise. */
fun nightFor(settings: AppSettings, systemNight: Boolean, now: LocalTime = LocalTime.now()): Boolean =
    if (usesSchedule(settings)) isNightNow(now, settings.nightFrom, settings.nightTo) else systemNight

const val AUTO_SYSTEM = "SYSTEM"
const val AUTO_SCHEDULE = "SCHEDULE"
