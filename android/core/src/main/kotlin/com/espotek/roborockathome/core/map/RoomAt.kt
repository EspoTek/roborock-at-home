package com.espotek.roborockathome.core.map

import kotlin.math.floor
import kotlin.math.sqrt

private val RAY_X = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
private val RAY_Y = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

/**
 * The room a tap at map point ([x], [y]) picks, or null when no room qualifies. Only [rooms] (the ones
 * the robot lists) can be picked. On a room's own pixel that is the room. Anywhere else on the plan
 * (a wall, a chair leg, a patch the robot never drove) eight rays go out from the point and the room
 * most of them meet first wins, the nearest on a tie: furniture in the middle of a kitchen is kitchen
 * even where another room is nearer through a wall. Off the plan a room has to be within [reach] map
 * pixels (the page allows 12 screen pixels for a finger, so `12 / scale`).
 */
fun roomAtOrNull(plan: FloorPlan, rooms: Set<Int>, x: Double, y: Double, reach: Double): Int? {
    val w = plan.width
    val h = plan.height
    val wx = floor(x).toInt()
    val wy = floor(y).toInt()
    val v0 = if (wx >= 0 && wy >= 0 && wx < w && wy < h) plan.classAt(wy * w + wx) else 0
    if (v0 >= ROOM_CLASS && v0 - ROOM_CLASS in rooms) return v0 - ROOM_CLASS
    // 30 map pixels is 1.5 m, further than the middle of a bed or a table is from its edge.
    val far = if (v0 != 0) 30.0 else reach
    // The rooms met, in the order first met, which is what settles a tie in both count and distance.
    val met = IntArray(RAY_X.size)
    val count = IntArray(RAY_X.size)
    val nearest = DoubleArray(RAY_X.size)
    var metCount = 0
    for (ray in RAY_X.indices) {
        val dx = RAY_X[ray]
        val dy = RAY_Y[ray]
        val step = sqrt((dx * dx + dy * dy).toDouble())
        var k = 1
        while (k * step <= far) {
            val px = wx + dx * k
            val py = wy + dy * k
            if (px >= 0 && py >= 0 && px < w && py < h) {
                val v = plan.classAt(py * w + px)
                if (v0 != 0 && v == 0) break   // a ray from inside the plan stops where the plan ends
                if (v >= ROOM_CLASS && v - ROOM_CLASS in rooms) {
                    var m = 0
                    while (m < metCount && met[m] != v - ROOM_CLASS) m++
                    if (m == metCount) { met[m] = v - ROOM_CLASS; nearest[m] = Double.POSITIVE_INFINITY; metCount++ }
                    count[m]++
                    nearest[m] = minOf(nearest[m], k * step)
                    break
                }
            }
            k++
        }
    }
    var room: Int? = null
    var bestCount = 0
    var bestDistance = Double.POSITIVE_INFINITY
    for (m in 0 until metCount) {
        if (count[m] > bestCount || (count[m] == bestCount && nearest[m] < bestDistance)) {
            room = met[m]; bestCount = count[m]; bestDistance = nearest[m]
        }
    }
    return room
}
