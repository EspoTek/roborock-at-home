package com.espotek.roborockathome.ui.schedules

import com.espotek.roborockathome.core.model.ActionType
import com.espotek.roborockathome.core.model.ScheduleAction
import com.espotek.roborockathome.core.model.ScheduleRequest
import com.espotek.roborockathome.fixtures.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDraftTest {
    private val downstairs = Fixtures.downstairsDocked
    private val studio = Fixtures.studioLowBattery
    private fun schedule(id: String) = Fixtures.schedules.single { it.id == id }

    // Downstairs, Friday 17:00, Living room, Hall and Guest room, turbo, named "Before guests arrive".
    private val beforeGuests = schedule("s_f18f63")

    @Test
    fun aNewDraftIsThePagesStartingPoint() {
        val request = ScheduleDraft.new(downstairs).toRequest()
        assertEquals(
            ScheduleRequest(
                robot = "downstairs",
                name = null,
                enabled = true,
                days = listOf(0, 1, 2, 3, 4),
                time = "09:30",
                action = ScheduleAction(ActionType.CleanAll, rooms = emptyList(), repeat = 1, fan = null, water = null),
            ),
            request,
        )
    }

    @Test
    fun roomsAndDaysGoInOrderAndTheServerNamesIt() {
        val draft = ScheduleDraft.new(downstairs).copy(
            wholeHome = false,
            rooms = linkedSetOf(22, 16, 18),
            passes = 2,
            days = setOf(6, 1, 3),
            time = "07:15",
            fan = "max",
            water = "mild",
        )
        val request = draft.toRequest()
        assertEquals(listOf(1, 3, 6), request.days)
        assertEquals(ScheduleAction(ActionType.CleanRooms, listOf(16, 18, 22), 2, "max", "mild"), request.action)
        assertNull(request.name)
    }

    @Test
    fun wholeHomeLeavesTheRoomsAndPassesOutButKeepsThemForPickRooms() {
        val rooms = ScheduleDraft.new(downstairs).copy(wholeHome = false, rooms = setOf(16, 17), passes = 3)
        val whole = rooms.copy(wholeHome = true)
        assertEquals(ScheduleAction(ActionType.CleanAll, emptyList(), 1, null, null), whole.toRequest().action)
        assertEquals(rooms.toRequest(), whole.copy(wholeHome = false).toRequest())
    }

    @Test
    fun aSavedScheduleOpensAsItIsSaved() {
        val draft = ScheduleDraft.of(beforeGuests)
        assertEquals("s_f18f63", draft.id)
        assertFalse(draft.wholeHome)
        assertEquals(setOf(16, 18, 22), draft.rooms)
        assertEquals(setOf(4), draft.days)
        assertEquals("turbo", draft.fan)
        // Saved again it goes without its name, as from the page, and the server names it afresh.
        assertEquals(
            ScheduleRequest("downstairs", null, true, listOf(4), "17:00", beforeGuests.action),
            draft.toRequest(),
        )
    }

    @Test
    fun anUnchangedDraftHasNoUnsavedChanges() {
        val saved = ScheduleDraft.of(beforeGuests)
        assertFalse(saved.changedFrom(saved))
        val new = ScheduleDraft.new(downstairs)
        assertFalse(new.changedFrom(new))
    }

    @Test
    fun anyChangeToWhatWouldBeSentIsAnUnsavedChange() {
        val saved = ScheduleDraft.of(beforeGuests)
        assertTrue(saved.copy(time = "17:30").changedFrom(saved))
        assertTrue(saved.copy(days = setOf(4, 5)).changedFrom(saved))
        assertTrue(saved.copy(rooms = setOf(16, 18)).changedFrom(saved))
        assertTrue(saved.copy(passes = 2).changedFrom(saved))
        assertTrue(saved.copy(fan = null).changedFrom(saved))
        assertTrue(saved.copy(water = "mild").changedFrom(saved))
        assertTrue(saved.copy(wholeHome = true).changedFrom(saved))
        assertTrue(saved.fitTo(Fixtures.upstairsCleaning).changedFrom(saved))
    }

    @Test
    fun goingThereAndBackIsNoChange() {
        val saved = ScheduleDraft.of(beforeGuests)
        assertFalse(saved.copy(days = saved.days.toggled(5).toggled(5)).changedFrom(saved))
        assertFalse(saved.copy(rooms = saved.rooms.toggled(17).toggled(17)).changedFrom(saved))
        // Passes on a whole-home draft are not sent, so changing them is no change.
        val whole = ScheduleDraft.of(schedule("s_137ff8"))
        assertFalse(whole.copy(passes = 3).changedFrom(whole))
    }

    @Test
    fun theDayShortcutsAreThePages() {
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), DayShortcut.EveryDay.days)
        assertEquals(setOf(0, 1, 2, 3, 4), DayShortcut.Weekdays.days)
        assertEquals(setOf(5, 6), DayShortcut.Weekends.days)
        assertEquals(listOf("Every day", "Weekdays", "Weekends"), DayShortcut.entries.map { it.label })
        val saved = ScheduleDraft.of(beforeGuests)
        assertEquals(listOf(5, 6), saved.copy(days = DayShortcut.Weekends.days).toRequest().days)
    }

    @Test
    fun aDayTogglesOnAndOff() {
        assertEquals(setOf(0, 4), setOf(0).toggled(4))
        assertEquals(setOf(0), setOf(0, 4).toggled(4))
    }

    @Test
    fun anotherRobotTakesNoRoomsAndOnlyTheLevelsItHas() {
        // Downstairs' Living room is 16, and so is Studio's: a different room on a different map.
        val saved = ScheduleDraft.of(beforeGuests).copy(water = "intense", passes = 2)
        val moved = saved.fitTo(studio)
        assertEquals("studio", moved.robot)
        assertEquals(emptySet<Int>(), moved.rooms)
        assertEquals("turbo", moved.fan)
        assertNull("Studio cannot mop", moved.water)
        assertEquals(2, moved.passes)
        assertEquals(saved.days, moved.days)
        assertEquals(saved.time, moved.time)
        assertFalse(moved.wholeHome)
        assertEquals("Pick at least one room, or choose Whole home.", moved.problemOrNull())
    }

    @Test
    fun theSameRobotKeepsTheRoomsItsMapStillHas() {
        val saved = ScheduleDraft.of(beforeGuests)
        assertEquals(saved, saved.fitTo(downstairs))
        val smaller = downstairs.copy(rooms = downstairs.rooms.filter { it.id != 22 }, fanOptions = listOf("quiet", "balanced"))
        val fitted = saved.fitTo(smaller)
        assertEquals(setOf(16, 18), fitted.rooms)
        assertNull(fitted.fan)
    }

    @Test
    fun aSaveNeedsADayAndInPickRoomsARoom() {
        val draft = ScheduleDraft.new(downstairs)
        assertNull(draft.problemOrNull())
        assertEquals("Pick at least one day.", draft.copy(days = emptySet()).problemOrNull())
        assertEquals("Pick at least one room, or choose Whole home.", draft.copy(wholeHome = false).problemOrNull())
        assertNull(draft.copy(wholeHome = false, rooms = setOf(17)).problemOrNull())
    }

    @Test
    fun theEditorsSavedFormComesBackTheSame() {
        val saved = ScheduleDraft.of(beforeGuests)
        val editing = Editing(saved, saved.copy(days = setOf(4, 5), fan = null))
        val restored = with(Editing.Saver) {
            val bundle = checkNotNull(androidx.compose.runtime.saveable.SaverScope { true }.save(editing))
            restore(bundle)
        }
        assertEquals(editing, restored)
        assertNull(with(Editing.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(null) })
    }
}
