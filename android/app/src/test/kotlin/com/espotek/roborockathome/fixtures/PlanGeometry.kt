package com.espotek.roborockathome.fixtures

import com.espotek.roborockathome.core.model.RoomGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

// What the server works out from a floor plan (rrmap.py), for a plan it never served: the synth plan.
// FixturesTest checks both against what the demo server says about its own plans.

/** The plan's `map.rev`: rrmap.py's digest, the CRC-32 of the width and height then the pixels. */
internal fun planRev(width: Int, height: Int, classes: ByteArray): String {
    val crc = CRC32()
    crc.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(width).putInt(height).array())
    crc.update(classes)
    return "%08x".format(crc.value)
}

/**
 * Each room's `map.rooms` entry, as rrmap.py's `_rooms` makes it: the bounding box, and the label
 * point in the middle of the widest run on the row nearest the room's mean height.
 */
internal fun roomGeometry(width: Int, classes: ByteArray, rooms: List<Int>): Map<Int, RoomGeometry> =
    rooms.sorted().associateWith { id ->
        val room = (32 + id).toByte()
        val first = classes.indexOf(room)
        check(first >= 0) { "Room $id is not on the plan" }
        val y0 = first / width
        val y1 = classes.lastIndexOf(room) / width
        var area = 0L
        var sumY = 0L
        var x0 = width
        var x1 = -1
        val widest = mutableListOf<Triple<Int, Int, Int>>() // row, run start, run end
        for (y in y0..y1) {
            var x = 0
            var inRow = 0
            var best = 0 to 0
            while (x < width) {
                if (classes[y * width + x] != room) {
                    x++
                    continue
                }
                val start = x
                while (x < width && classes[y * width + x] == room) x++
                if (inRow == 0) x0 = minOf(x0, start)
                inRow += x - start
                if (x - start > best.second - best.first) best = start to x
                x1 = maxOf(x1, x - 1)
            }
            if (inRow == 0) continue
            area += inRow
            sumY += y.toLong() * inRow
            widest += Triple(y, best.first, best.second)
        }
        val meanY = sumY.toDouble() / area + 0.5
        val (row, a, b) = widest.minBy { kotlin.math.abs(it.first + 0.5 - meanY) }
        RoomGeometry(cx = (a + b) / 2.0, cy = row + 0.5, x0 = x0, y0 = y0, x1 = x1, y1 = y1)
    }
