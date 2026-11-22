package com.espotek.roborockathome.ui.activity

import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HistoryKind
import com.espotek.roborockathome.fixtures.Fixtures
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DayGroupsTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    @Test
    fun theFixturesThreeDaysMakeThreeGroupsNewestFirst() {
        val groups = groupByDay(Fixtures.history, melbourne)
        assertEquals(listOf(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 20)), groups.map { it.date })
        assertEquals(Fixtures.history, groups.flatMap { it.entries })
        assertEquals(Fixtures.history.size, groups.sumOf { it.entries.size })
    }

    @Test
    fun theDayTurnsAtMidnightOnTheClockGiven() {
        fun at(day: Int, hour: Int, minute: Int) =
            ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, melbourne).toEpochSecond()
        val history = listOf(
            HistoryEntry(at(22, 0, 1), "downstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(21, 23, 59), "downstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(21, 23, 59), "upstairs", HistoryKind.Docked, "Back on the dock"),
        )
        assertEquals(listOf(listOf(history[0]), history.drop(1)), groupByDay(history, melbourne).map { it.entries })
        // Eleven hours behind Melbourne, in UTC, all three fall on the 21st.
        assertEquals(listOf(LocalDate.of(2026, 11, 21)), groupByDay(history, ZoneId.of("UTC")).map { it.date })
    }

    @Test
    fun noHistoryIsNoGroups() {
        assertEquals(emptyList<DayGroup>(), groupByDay(emptyList(), melbourne))
    }
}
