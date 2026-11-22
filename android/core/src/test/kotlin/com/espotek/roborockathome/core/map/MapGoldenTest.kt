package com.espotek.roborockathome.core.map

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The port gives the page's answers on each fixture map. */
@RunWith(Parameterized::class)
class MapGoldenTest(private val name: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun maps() = Golden.MAPS
    }

    private val g = Golden.json("golden/$name.json")
    private val plan = Golden.plan(name)

    private fun rooms(list: String) = g.obj("slots").obj(list).getValue("rooms").ints()
    private fun slots(list: String) = g.obj("slots").obj(list).getValue("slots").slotPairs()

    @Test
    fun adjacency() {
        val expected = g.arr("contacts").map { it.ints().let { (a, b, n) -> Contact(a, b, n) } }
        assertEquals(expected, adjacency(plan))
    }

    @Test
    fun slots() {
        val contacts = adjacency(plan)
        for (list in listOf("full", "partial")) {
            val got = assignSlots(rooms(list), contacts, RoomPalette.distances)
            assertEquals("$name, $list room list", slots(list), got.toList())
        }
    }

    @Test
    fun hits() {
        val grid = g.obj("grid")
        val nx = grid.int("nx")
        val ny = grid.int("ny")
        val wrong = ArrayList<String>()
        var checked = 0
        for (case in g.arr("hits").map { it.jsonObject }) {
            val list = case.getValue("rooms").jsonPrimitive.content
            val rooms = rooms(list).toSet()
            val reach = case.double("reach")
            val expected = case.getValue("rle").runs()
            assertEquals("$name: points in the $list rooms, reach $reach case", nx * ny, expected.size)
            for (j in 0 until ny) for (i in 0 until nx) {
                val x = grid.double("x0") + i * grid.double("step")
                val y = grid.double("y0") + j * grid.double("step")
                val got = roomAtOrNull(plan, rooms, x, y, reach)
                if (got != expected[j * nx + i]) wrong.add("($x, $y) reach $reach, $list rooms: page ${expected[j * nx + i]}, port $got")
                checked++
            }
        }
        if (wrong.isNotEmpty()) fail("$name: ${wrong.size} of $checked taps differ, first: ${wrong.take(10).joinToString("; ")}")
    }

    @Test
    fun paint() {
        for (case in g.arr("paints").map { it.jsonObject }) {
            val list = case.getValue("rooms").jsonPrimitive.content
            val selected = case.getValue("selected").ints().toSet()
            val tones = mapOf("light" to RoomPalette.light, "dark" to RoomPalette.dark).getValue(case.getValue("theme").jsonPrimitive.content)
            val file = case.getValue("file").jsonPrimitive.content
            val expected = Golden.pixels(file, plan.width * plan.height)
            val got = paintFloorPlan(plan, tones, slots(list).toMap(), selected)
            val wrong = expected.indices.filter { expected[it] != got[it] }
            if (wrong.isNotEmpty()) {
                val first = wrong.take(10).joinToString("; ") { i ->
                    "(${i % plan.width}, ${i / plan.width}) class ${plan.classAt(i)}: page %08x, port %08x".format(expected[i], got[i])
                }
                fail("$file: ${wrong.size} pixels differ, first: $first")
            }
        }
    }
}
