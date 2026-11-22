package com.espotek.roborockathome.core.map

/**
 * A robot's floor plan as `/map.bin` serves it: one class per pixel, row by row. 0 is outside the
 * plan, 1 a wall, 2 floor in no room, 3 seen but never driven, and 32 + id a room.
 */
class FloorPlan(val width: Int, val height: Int, val classes: ByteArray) {
    init {
        require(classes.size == width * height) { "The map has ${classes.size} bytes, expected ${width * height}." }
    }

    /** The class of pixel [i] (row by row), 0 to 255. */
    internal fun classAt(i: Int): Int = classes[i].toInt() and 0xFF
}

/** A room's pixels hold this plus the room's id. */
internal const val ROOM_CLASS = 32
