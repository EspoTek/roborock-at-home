package com.espotek.roborockathome.fixtures

import com.espotek.roborockathome.core.model.HistoryKind
import com.espotek.roborockathome.core.state.Home
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixturesTest {
    private val homes = listOf(
        Fixtures.demo, Fixtures.cleaningRooms, Fixtures.offlineRobot, Fixtures.singleRobot,
        Fixtures.groundFloorHome, Fixtures.notLinked,
    )

    // The synth plan's geometry is worked out here, so the working must give what the server says
    // about the plans it serves.
    @Test
    fun roomGeometryIsTheServers() {
        for (robot in Fixtures.demo.robots) {
            val map = checkNotNull(robot.map)
            val plan = Fixtures.demo.plans.getValue(robot.id)
            assertEquals(robot.name, map.rooms, roomGeometry(map.w, plan.bytes, map.rooms.keys.toList()))
            assertEquals(robot.name, map.rev, planRev(map.w, map.h, plan.bytes))
        }
    }

    @Test
    fun everyRobotHasItsOwnPlanAndEverythingIsAboutItsRobots() {
        for (home in homes) {
            val ids = home.robots.map { it.id }.toSet()
            assertEquals(ids, home.plans.keys)
            for (robot in home.robots) assertEquals(checkNotNull(robot.map).rev, home.plans.getValue(robot.id).rev)
            assertTrue(ids.containsAll(home.paths.keys))
            assertTrue(home.schedules.all { it.robot in ids })
            assertTrue(checkNotNull(home.history).all { it.robot in ids })
        }
    }

    @Test
    fun groundFloorHasFourteenRoomsWithAnUnnamedOneLast() {
        val robot = Fixtures.groundFloor
        assertEquals(14, robot.rooms.size)
        assertEquals("Unnamed room", robot.rooms.last().name)
        assertTrue(robot.rooms.any { it.name.length > 25 })
        val map = checkNotNull(robot.map)
        assertEquals(robot.rooms.map { it.id }.sorted(), map.rooms.keys.toList())
        val plan = Fixtures.groundFloorHome.plans.getValue(robot.id)
        fun roomAt(x: Double, y: Double) = (plan.bytes[y.toInt() * plan.w + x.toInt()].toInt() and 0xFF) - 32
        for ((id, box) in map.rooms) assertEquals("label of room $id", id, roomAt(box.cx, box.cy))
        val pos = checkNotNull(robot.pos)
        assertEquals(pos.room, roomAt(pos.x, pos.y))
        val charger = checkNotNull(map.charger)
        assertEquals(pos.room, roomAt(charger.x, charger.y))
    }

    @Test
    fun historySpansThreeDaysNewestFirstWithEveryKind() {
        val history = checkNotNull(Fixtures.demo.history)
        assertEquals(history.sortedByDescending { it.ts }, history)
        val days = history.map { Instant.ofEpochSecond(it.ts).atZone(ZoneId.of("Australia/Melbourne")).toLocalDate() }.toSet()
        assertEquals(3, days.size)
        assertEquals(HistoryKind.entries.toSet(), history.map { it.kind }.toSet())
    }

    @Test
    fun schedulesHaveTheShapesTheScreensNeed() {
        val schedules = Fixtures.schedules
        assertTrue(schedules.any { !it.enabled && it.nextRun == null })
        assertTrue(schedules.any { it.lastRun?.ok == false })
        assertTrue(schedules.any { it.action.rooms.size >= 8 })
        assertEquals(Fixtures.demo.robots.map { it.id }.toSet(), schedules.map { it.robot }.toSet())
    }

    @Test
    fun robotsInTheStatesTheirNamesSay() {
        assertEquals("Cleaning rooms", Fixtures.downstairsCleaningRooms.stateLabel)
        assertEquals(false, checkNotNull(Fixtures.garageJammed.pos).live)
        assertEquals(false, Fixtures.studioOffline.online)
        assertEquals(false, checkNotNull(Fixtures.studioOffline.pos).live)
        assertTrue(Fixtures.notLinked.app.let { it != null && !it.account.linked })
        assertEquals(listOf<Home>(), homes.filter { it.session == null })
    }
}
