package com.espotek.roborockathome.core.map

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The palette, the distances between its slots and the page's own edge cases of the assignment. */
class PaletteGoldenTest {
    private val g = Golden.json("golden/palette.json")
    private val themes = mapOf("light" to RoomPalette.light, "dark" to RoomPalette.dark)

    @Test
    fun paletteHasThePagesColours() {
        for ((name, tones) in themes) {
            val page = g.obj(name)
            assertEquals("$name wall", page.int("wall"), tones.wall)
            assertEquals("$name floor", page.int("floor"), tones.floor)
            assertEquals("$name seen", page.int("seen"), tones.seen)
            val slots = page.arr("slots").map { it.ints().let { (normal, selected, edge) -> SlotColours(normal, selected, edge) } }
            assertEquals("$name slots", slots, tones.slots)
        }
    }

    @Test
    fun oklabMatchesThePage() {
        for ((name, tones) in themes) {
            val page = g.obj("oklab").arr(name)
            for ((i, slot) in tones.slots.withIndex()) {
                val expected = page[i].jsonArray.map { it.jsonPrimitive.double }
                val got = oklab(slot.normal)
                for (c in 0 until 3) assertEquals("$name slot $i component $c", expected[c], got[c], 1e-9)
            }
        }
    }

    @Test
    fun distancesMatchThePage() {
        val page = g.arr("distances")
        val d = RoomPalette.distances
        assertEquals(page.size, d.size)
        for (i in 0 until d.size) for (j in 0 until d.size) {
            assertEquals("distance $i, $j", page[i].jsonArray[j].jsonPrimitive.double, d[i, j], 1e-9)
        }
    }

    @Test
    fun roomsWithNoMapTakeTheSlotsInOrder() {
        val case = Golden.json("golden/edge-cases.json").obj("noMap")
        val got = assignSlots(case.getValue("rooms").ints(), emptyList(), RoomPalette.distances)
        assertEquals(case.getValue("slots").slotPairs(), got.toList())
        assertEquals(listOf(16 to 0, 17 to 1, 18 to 2), got.toList())
    }

    @Test
    fun thirtyTwoRoomsInARowNeverShareASlotWithANeighbour() {
        val case = Golden.json("golden/edge-cases.json").obj("chain")
        val contacts = case.arr("contacts").map { it.ints().let { (a, b, n) -> Contact(a, b, n) } }
        val got = assignSlots(case.getValue("rooms").ints(), contacts, RoomPalette.distances)
        assertEquals(case.getValue("slots").slotPairs(), got.toList())
        for (c in contacts) assertNotEquals("rooms ${c.a} and ${c.b}", got.getValue(c.a), got.getValue(c.b))
    }
}
