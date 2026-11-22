package com.espotek.roborockathome.core.map

/**
 * The colour of every pixel of [plan], ARGB row by row (`width * height`), transparent outside the
 * plan. [slots] gives the slot of each room the robot lists ([assignSlots]); a room in [selected] is
 * drawn in its slot's deeper shade with a one-pixel edge.
 */
fun paintFloorPlan(plan: FloorPlan, tones: Tones, slots: Map<Int, Int>, selected: Set<Int>): IntArray {
    // Whatever is not wall or unexplored is plain floor, including segments the robot does not list
    // as rooms: only what can be picked is drawn as a room.
    val fill = IntArray(256) { tones.floor }
    fill[0] = 0
    fill[1] = tones.wall
    fill[3] = tones.seen
    val edge = IntArray(256)
    for ((id, slot) in slots) {
        val c = tones.slots[slot]
        val on = id in selected
        fill[ROOM_CLASS + id] = if (on) c.selected else c.normal
        if (on) edge[ROOM_CLASS + id] = c.edge
    }
    val w = plan.width
    val h = plan.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val v = plan.classAt(i)
        // A selected room gets a one-pixel edge in a deeper shade so it reads as chosen.
        val onEdge = edge[v] != 0 && (x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
            plan.classAt(i - 1) != v || plan.classAt(i + 1) != v || plan.classAt(i - w) != v || plan.classAt(i + w) != v)
        pixels[i] = if (onEdge) edge[v] else fill[v]
    }
    return pixels
}
