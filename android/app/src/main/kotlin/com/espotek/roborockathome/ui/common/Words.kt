package com.espotek.roborockathome.ui.common

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Times, dates and durations in the web page's words ("Tomorrow 9:00 AM", "3 min ago", "1 h 5 min"),
 * in the device's language and its 12 or 24 hour choice: every screen words them here. A moment is
 * seen from [now] on the clock of [zone], the device's unless [inZone] gives another, such as the
 * server's for schedules, which run in it. Get them from [rememberTimeWords].
 */
@Immutable
class TimeWords internal constructor(val now: Instant, val zone: ZoneId, private val formats: TimeFormats) {
    val use24Hour: Boolean get() = formats.use24Hour

    /** The same words on the clock of [zone]. */
    fun inZone(zone: ZoneId): TimeWords = TimeWords(now, zone, formats)

    val today: LocalDate get() = now.atZone(zone).toLocalDate()

    /** The calendar day of moment [ts], in seconds. */
    fun date(ts: Long): LocalDate = local(ts).toLocalDate()

    /** A time of day written `HH:MM`, such as a schedule's: "9:30 AM", or "09:30" on a phone set to 24 hours. */
    fun time(hhmm: String): String = formats.time.write(LocalTime.parse(hhmm).atDate(A_MONDAY))

    /** The time of day of moment [ts]. */
    fun time(ts: Long): String = formats.time.write(local(ts))

    /** "Mon". */
    fun weekday(day: DayOfWeek): String = formats.weekday.write(A_MONDAY.plusDays(day.ordinal.toLong()).atStartOfDay())

    /** "Monday". */
    fun weekdayInFull(day: DayOfWeek): String = formats.weekdayInFull.write(A_MONDAY.plusDays(day.ordinal.toLong()).atStartOfDay())

    /** The day and month of moment [ts]: "22 Nov", or "Nov 22", as the device writes it. */
    fun dayMonth(ts: Long): String = formats.dayMonth.write(local(ts))

    /** The page's `day`: "Today", "Yesterday", "Tomorrow", the weekday within a week either way, else the day and month. */
    fun day(ts: Long): String = when (val days = ChronoUnit.DAYS.between(today, date(ts))) {
        0L -> "Today"
        -1L -> "Yesterday"
        1L -> "Tomorrow"
        else -> if (abs(days) < 7) weekday(date(ts).dayOfWeek) else dayMonth(ts)
    }

    /** The page's `when`: "Tomorrow 9:00 AM". */
    fun moment(ts: Long): String = "${day(ts)} ${time(ts)}"

    /** The page's `at`, for within a sentence: "at 4:45 PM" today, "yesterday at 4:45 PM", else "on Tue at 4:45 PM". */
    fun at(ts: Long): String = when (ChronoUnit.DAYS.between(today, date(ts))) {
        0L -> "at ${time(ts)}"
        -1L -> "yesterday at ${time(ts)}"
        else -> "on ${day(ts)} at ${time(ts)}"
    }

    /** A heading for one day of a history: "Today", "Yesterday", then the weekday and date, "Fri 20 Nov". */
    fun dayHeading(date: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> formats.dayAndDate.write(date.atStartOfDay())
    }

    /** The page's `ago`: "just now", "3 min ago", "2 h ago", and from 12 hours back the [moment]. */
    fun ago(ts: Long): String {
        val seconds = now.toEpochMilli() / 1000.0 - ts
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${(seconds / 60).roundToLong()} min ago"
            seconds < 43200 -> "${(seconds / 3600).roundToLong()} h ago"
            else -> moment(ts)
        }
    }

    /** The page's `until`: "in a moment", "in 5 min", "in 3 h", or null when it is 12 hours or more away. */
    fun untilOrNull(ts: Long): String? {
        val seconds = ts - now.toEpochMilli() / 1000.0
        return when {
            seconds < 60 -> "in a moment"
            seconds < 3600 -> "in ${(seconds / 60).roundToLong()} min"
            seconds < 43200 -> "in ${(seconds / 3600).roundToLong()} h"
            else -> null
        }
    }

    /** How long something has run: "16 min", "2 h 5 min". At least a minute, and never "60 min". */
    fun duration(seconds: Long): String {
        val minutes = maxOf(1, (seconds / 60.0).roundToLong())
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun local(ts: Long): LocalDateTime = Instant.ofEpochSecond(ts).atZone(zone).toLocalDateTime()

    private companion object {
        // Any Monday: a wall-clock time, or a weekday, is written as one on this date.
        val A_MONDAY: LocalDate = LocalDate.of(2024, 1, 1)
    }
}

/** How the device writes times and dates, made once for its language and its 12 or 24 hour choice. */
internal class TimeFormats(locale: Locale, val use24Hour: Boolean) {
    val time = WallClock(if (use24Hour) "Hm" else "hm", locale)
    val weekday = WallClock("EEE", locale)
    val weekdayInFull = WallClock("EEEE", locale)
    val dayMonth = WallClock("dMMM", locale)
    val dayAndDate = WallClock("EEEdMMM", locale)
}

/**
 * Writes a date and time as a wall clock shows it, java.time having already put the moment on the
 * right clock. ICU, not java.time, writes it: the patterns ICU picks for a language can use letters
 * that older versions of java.time on Android do not know.
 */
internal class WallClock(skeleton: String, locale: Locale) {
    private val icu = DateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = TimeZone.GMT_ZONE }

    fun write(at: LocalDateTime): String = icu.format(Date(at.toEpochSecond(ZoneOffset.UTC) * 1000))
}

/** A robot's own word for a suction or mop water level, as the page's `cap` shows it: "max_plus" is "Max plus". */
fun levelLabel(level: String): String = level.replaceFirstChar { it.uppercase() }.replace('_', ' ')

/** "Kitchen", "Kitchen and Hall", "Kitchen, Hall and Study", as the server and the page join names. */
fun joinNames(names: List<String>): String {
    require(names.isNotEmpty()) { "Nothing to join" }
    return if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
