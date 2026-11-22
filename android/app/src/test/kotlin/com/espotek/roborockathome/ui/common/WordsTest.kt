package com.espotek.roborockathome.ui.common

import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.timeWords
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NOW = Fixtures.RECORDED // Sunday 22 November 2026, 18:00 in Melbourne
private const val MINUTE = 60L
private const val HOUR = 3600L
private const val DAY = 86400L

// Robolectric for Android's ICU, which writes the times.
@RunWith(RobolectricTestRunner::class)
class WordsTest {
    private val words = timeWords()

    @Test
    fun agoCountsMinutesThenHoursThenNamesTheDay() {
        assertEquals("just now", words.ago(NOW - 59))
        assertEquals("1 min ago", words.ago(NOW - 60))
        assertEquals("3 min ago", words.ago(NOW - 3 * MINUTE))
        assertEquals("59 min ago", words.ago(NOW - 59 * MINUTE))
        assertEquals("1 h ago", words.ago(NOW - HOUR))
        assertEquals("12 h ago", words.ago(NOW - 12 * HOUR + 1))
        assertEquals("Today 6:00\u202FAM", words.ago(NOW - 12 * HOUR))
        assertEquals("Yesterday 5:00\u202FPM", words.ago(NOW - DAY - HOUR))
        assertEquals("Wed 6:00\u202FPM", words.ago(NOW - 4 * DAY))
        assertEquals("Nov 15 6:00\u202FPM", words.ago(NOW - 7 * DAY))
    }

    @Test
    fun atReadsWithinASentence() {
        assertEquals("at 6:00 PM", words.at(NOW))
        assertEquals("yesterday at 5:00 PM", words.at(NOW - DAY - HOUR))
        assertEquals("on Wed at 6:00 PM", words.at(NOW - 4 * DAY))
    }

    @Test
    fun aMomentInTheFutureIsJustNow() {
        // A server clock a little ahead of the phone's.
        assertEquals("just now", words.ago(NOW + 5))
    }

    @Test
    fun untilCountsDownAndStopsTwelveHoursOut() {
        assertEquals("in a moment", words.untilOrNull(NOW + 59))
        assertEquals("in 5 min", words.untilOrNull(NOW + 5 * MINUTE))
        assertEquals("in 3 h", words.untilOrNull(NOW + 3 * HOUR))
        assertNull(words.untilOrNull(NOW + 12 * HOUR))
        assertEquals("Tomorrow 9:30\u202FAM", words.moment(NOW + 15 * HOUR + 30 * MINUTE))
    }

    @Test
    fun durationIsAtLeastAMinuteAndNeverSixtyMinutes() {
        assertEquals("1 min", words.duration(0))
        assertEquals("16 min", words.duration(965))
        assertEquals("1 h 0 min", words.duration(HOUR - 1))
        assertEquals("1 h 5 min", words.duration(HOUR + 5 * MINUTE))
        assertEquals("26 h 0 min", words.duration(DAY + 2 * HOUR))
    }

    @Test
    fun timesFollowThePhonesHours() {
        assertEquals("9:30\u202FAM", words.time("09:30"))
        assertEquals("6:00\u202FPM", words.time(NOW))
        val day = timeWords(use24Hour = true)
        assertEquals("09:30", day.time("09:30"))
        assertEquals("18:00", day.time(NOW))
    }

    @Test
    fun daysAndDatesFollowThePhonesLanguage() {
        assertEquals("Mon", words.weekday(DayOfWeek.MONDAY))
        assertEquals("Sunday", words.weekdayInFull(DayOfWeek.SUNDAY))
        assertEquals("Nov 22", words.dayMonth(NOW))
        assertEquals("22 Nov", timeWords(Locale.UK).dayMonth(NOW))
    }

    @Test
    fun aHistoryDayIsTodayYesterdayThenItsDate() {
        assertEquals("Today", words.dayHeading(LocalDate.of(2026, 11, 22)))
        assertEquals("Yesterday", words.dayHeading(LocalDate.of(2026, 11, 21)))
        assertEquals("Fri, Nov 20", words.dayHeading(LocalDate.of(2026, 11, 20)))
    }

    @Test
    fun anotherZoneHasItsOwnToday() {
        // 18:00 in Melbourne is 07:00 the same day in UTC, and 23:00 the day before in Los Angeles.
        assertEquals(LocalDate.of(2026, 11, 22), words.inZone(ZoneId.of("UTC")).today)
        assertEquals("Yesterday 11:00\u202FPM", words.inZone(ZoneId.of("America/Los_Angeles")).moment(NOW - DAY))
        assertEquals("11:00\u202FPM", words.inZone(ZoneId.of("America/Los_Angeles")).time(NOW))
    }

    @Test
    fun levelsAsThePageCapitalisesThem() {
        assertEquals("Balanced", levelLabel("balanced"))
        assertEquals("Max plus", levelLabel("max_plus"))
        assertEquals("Customised by room", levelLabel("customised_by_room"))
    }

    @Test
    fun names() {
        assertEquals("Agent", joinNames(listOf("Agent")))
        assertEquals("Agent and Home Assistant", joinNames(listOf("Agent", "Home Assistant")))
        assertEquals("Agent, Garage door and Home Assistant", joinNames(listOf("Agent", "Garage door", "Home Assistant")))
        assertThrows(IllegalArgumentException::class.java) { joinNames(emptyList()) }
    }
}
