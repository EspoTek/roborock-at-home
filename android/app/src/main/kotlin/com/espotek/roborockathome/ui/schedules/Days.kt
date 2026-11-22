package com.espotek.roborockathome.ui.schedules

import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.joinNames
import java.time.DayOfWeek

// A schedule's days in the server's numbering, 0 for Monday to 6 for Sunday, in the page's words.

/** "Mon" for 0 through "Sun" for 6. */
internal fun TimeWords.dayShort(day: Int): String = weekday(DayOfWeek.of(day + 1))

/** "Monday" for 0 through "Sunday" for 6. */
internal fun TimeWords.dayFull(day: Int): String = weekdayInFull(DayOfWeek.of(day + 1))

/** The page's `daysText`: "Every day", "Weekdays", "Weekends", or the days, "Tue, Thu". */
internal fun TimeWords.days(days: Collection<Int>): String = when (val sorted = days.sorted()) {
    DayShortcut.EveryDay.days.sorted() -> DayShortcut.EveryDay.label
    DayShortcut.Weekdays.days.sorted() -> DayShortcut.Weekdays.label
    DayShortcut.Weekends.days.sorted() -> DayShortcut.Weekends.label
    else -> sorted.joinToString(", ") { dayShort(it) }
}

/** The days as they are said: "every day", "on weekdays", "on Tuesday and Thursday". */
internal fun TimeWords.daysSpoken(days: Collection<Int>): String = when (val sorted = days.sorted()) {
    DayShortcut.EveryDay.days.sorted() -> "every day"
    DayShortcut.Weekdays.days.sorted() -> "on weekdays"
    DayShortcut.Weekends.days.sorted() -> "at weekends"
    else -> "on " + joinNames(sorted.map { dayFull(it) })
}
