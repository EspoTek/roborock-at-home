package com.espotek.roborockathome.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.espotek.roborockathome.core.map.FloorPlan
import com.espotek.roborockathome.core.map.RoomPalette
import com.espotek.roborockathome.core.map.adjacency
import com.espotek.roborockathome.core.map.assignSlots
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.Plan
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.ui.theme.RahTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomColoursTest {
    @get:Rule
    val compose = createComposeRule()

    private val home = Fixtures.groundFloorHome
    private val robot = Fixtures.groundFloor
    private val ids = robot.rooms.map { it.id }

    private class Case(val home: Home, val robot: Robot, val dark: Boolean) {
        var colours: RoomColours? = null
    }

    @Test
    fun theSlotsAreCoresForTheRobotsRoomsInItsOrderOnItsPlan() {
        val plan = home.plans.getValue(robot.id)
        val expected = assignSlots(ids, adjacency(FloorPlan(plan.w, plan.h, plan.bytes)), RoomPalette.distances)
        val (light, dark) = colours(Case(home, robot, dark = false), Case(home, robot, dark = true)).map(::checkNotNull)
        assertEquals(expected, light.slots)
        assertSame(RoomPalette.light, light.tones)
        for (id in ids) assertEquals(RoomPalette.light.slots[expected.getValue(id)], light.of(id))
        assertThrows(IllegalStateException::class.java) { light.of(99) }
        assertEquals(expected, dark.slots)
        assertSame(RoomPalette.dark, dark.tones)
    }

    @Test
    fun noColoursUntilThePlanForTheRobotsMapArrives() {
        val old = home.plans.getValue(robot.id).let { Plan("0ld0ld00", it.w, it.h, it.bytes) }
        val colours = colours(
            Case(home.copy(plans = emptyMap()), robot, dark = false),
            Case(home.copy(plans = mapOf(robot.id to old)), robot, dark = false),
        )
        assertEquals(listOf(null, null), colours)
    }

    @Test
    fun aRobotWithNoMapHasColoursAtOnce() {
        val (colours) = colours(Case(home.copy(plans = emptyMap()), robot.copy(map = null), dark = false))
        assertEquals(assignSlots(ids, emptyList(), RoomPalette.distances), checkNotNull(colours).slots)
    }

    @Test
    fun theyAreRememberedWhileNothingTheyDependOnChanges() {
        val seen = mutableListOf<RoomColours?>()
        var shown by mutableStateOf(home)
        compose.setContent {
            RahTheme(dark = false) { seen += rememberRoomColours(shown, robot) }
        }
        compose.runOnIdle { shown = home.copy(history = emptyList(), robots = listOf(robot.copy(battery = 50))) }
        compose.waitForIdle()
        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
    }

    private fun colours(vararg cases: Case): List<RoomColours?> {
        compose.setContent {
            for (case in cases) RahTheme(case.dark) { case.colours = rememberRoomColours(case.home, case.robot) }
        }
        compose.waitForIdle()
        return cases.map { it.colours }
    }
}
