package com.espotek.roborockathome.core.map

import org.junit.Assert.assertTrue
import org.junit.Test

class SlotTimingTest {
    @Test
    fun fourteenRoomsAreColouredQuickly() {
        val plan = Golden.plan("synth")
        val rooms = (16..29).toList()
        val contacts = adjacency(plan)
        repeat(50) { assignSlots(rooms, contacts, RoomPalette.distances) }
        val ms = List(50) {
            val t0 = System.nanoTime()
            assignSlots(rooms, contacts, RoomPalette.distances)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        println("assignSlots, synth (14 rooms) after warm-up: median %.2f ms, slowest %.2f ms".format(ms[ms.size / 2], ms.last()))
        assertTrue("median ${ms[ms.size / 2]} ms", ms[ms.size / 2] < 100)
    }
}
