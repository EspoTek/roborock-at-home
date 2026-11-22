package com.espotek.roborockathome.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    private fun label(room: Int, x0: Double, y0: Double, x1: Double, y1: Double, cx: Double, cy: Double, w: Double = 20.0, h: Double = 10.0) =
        LabelSpec(room, RoomBox(cx, cy, x0, y0, x1, y1), w, h)

    @Test
    fun nameIsCentredOnItsRoomInTheView() {
        val placed = layoutLabels(listOf(label(16, 10.0, 10.0, 60.0, 40.0, cx = 35.0, cy = 25.0, w = 30.0, h = 12.0)), scale = 2.0, tx = 10.0, ty = -5.0, pin = null)
        assertEquals(listOf(PlacedLabel(16, 10 + 70.0 - 15, -5 + 50.0 - 6)), placed)
    }

    @Test
    fun nameIsKeptInsideItsRoomsBox() {
        // Middles close to the left and to the right edge of the box, as in an L-shaped room.
        val left = layoutLabels(listOf(label(16, 10.0, 10.0, 60.0, 40.0, cx = 11.0, cy = 25.0, w = 30.0, h = 12.0)), 2.0, 10.0, -5.0, null)
        val right = layoutLabels(listOf(label(16, 10.0, 10.0, 60.0, 40.0, cx = 59.0, cy = 39.0, w = 30.0, h = 12.0)), 2.0, 10.0, -5.0, null)
        assertEquals(listOf(PlacedLabel(16, 10 + 20.0 + 2, -5 + 50.0 - 6)), left)
        assertEquals(listOf(PlacedLabel(16, 10 + 120.0 - 30 - 2, -5 + 80.0 - 12 - 2)), right)
    }

    @Test
    fun nameThatDoesNotFitItsRoomIsLeftOut() {
        // The box must be wider than the name by more than 8 and taller by more than 4.
        val narrow = label(16, 0.0, 0.0, 28.0, 100.0, cx = 14.0, cy = 50.0)
        val low = label(17, 200.0, 0.0, 300.0, 14.0, cx = 250.0, cy = 7.0)
        assertEquals(emptyList<PlacedLabel>(), layoutLabels(listOf(narrow, low), 1.0, 0.0, 0.0, null))
        assertEquals(listOf(16, 17), layoutLabels(listOf(narrow, low), 1.01, 0.0, 0.0, null).map { it.room })
    }

    @Test
    fun biggerRoomKeepsItsNameWhereTwoCollide() {
        val small = label(16, 0.0, 0.0, 99.0, 100.0, cx = 75.5, cy = 50.0)
        val big = label(17, 0.0, 0.0, 100.0, 100.0, cx = 50.0, cy = 50.0)
        assertEquals(listOf(PlacedLabel(17, 40.0, 45.0)), layoutLabels(listOf(small, big), 1.0, 0.0, 0.0, null))
        assertEquals(listOf(PlacedLabel(17, 40.0, 45.0)), layoutLabels(listOf(big, small), 1.0, 0.0, 0.0, null))
    }

    @Test
    fun namesKeepSixPixelsApartSideBySideAndTwoAboveAndBelow() {
        val big = label(17, 0.0, 0.0, 100.0, 100.0, cx = 50.0, cy = 50.0)
        fun shown(cx: Double, cy: Double) = layoutLabels(listOf(big, label(16, 0.0, 0.0, 99.0, 100.0, cx, cy)), 1.0, 0.0, 0.0, null).map { it.room }
        assertEquals(listOf(17, 16), shown(cx = 76.0, cy = 50.0))
        assertEquals(listOf(17), shown(cx = 75.5, cy = 50.0))
        assertEquals(listOf(17, 16), shown(cx = 50.0, cy = 62.0))
        assertEquals(listOf(17), shown(cx = 50.0, cy = 61.5))
    }

    @Test
    fun nameLeftOutDoesNotPushOthersOut() {
        // The biggest room is too narrow for its name, so the smaller room's name may sit there.
        val tall = label(16, 40.0, 0.0, 66.0, 500.0, cx = 53.0, cy = 50.0)
        val small = label(17, 0.0, 0.0, 100.0, 100.0, cx = 50.0, cy = 50.0)
        assertEquals(listOf(PlacedLabel(17, 40.0, 45.0)), layoutLabels(listOf(tall, small), 1.0, 0.0, 0.0, null))
    }

    @Test
    fun biggestRoomsComeFirstAndEqualOnesKeepTheRobotsOrder() {
        val rooms = listOf(
            label(16, 0.0, 0.0, 40.0, 40.0, cx = 20.0, cy = 20.0),
            label(17, 100.0, 0.0, 200.0, 100.0, cx = 150.0, cy = 50.0),
            label(18, 300.0, 0.0, 340.0, 40.0, cx = 320.0, cy = 20.0),
            label(19, 400.0, 0.0, 460.0, 60.0, cx = 430.0, cy = 30.0),
        )
        assertEquals(listOf(17, 19, 16, 18), layoutLabels(rooms, 1.0, 0.0, 0.0, null).map { it.room })
    }

    @Test
    fun lastSeenPinKeepsNamesClear() {
        val rooms = listOf(label(16, 0.0, 0.0, 100.0, 100.0, cx = 50.0, cy = 50.0), label(17, 200.0, 0.0, 280.0, 100.0, cx = 240.0, cy = 50.0))
        assertEquals(listOf(17), layoutLabels(rooms, 1.0, 0.0, 0.0, ScreenRect(30.0, 40.0, 16.0, 12.0)).map { it.room })
        assertEquals(listOf(16, 17), layoutLabels(rooms, 1.0, 0.0, 0.0, ScreenRect(30.0, 10.0, 16.0, 12.0)).map { it.room })
    }
}
