package com.espotek.roborockathome.core.map

/** A room's box and the point its name is centred on, in map pixels (`map.rooms` in the API). */
data class RoomBox(val cx: Double, val cy: Double, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

/** A room's name to place: the room, its box, and the size the name measures on screen. */
data class LabelSpec(val room: Int, val box: RoomBox, val width: Double, val height: Double)

/** A rectangle on screen: its top-left corner and its size. */
data class ScreenRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A name to draw, with the top-left corner of its text on screen. */
data class PlacedLabel(val room: Int, val x: Double, val y: Double)

/**
 * The room names to draw and where, for the view that puts map point (mx, my) on screen at
 * (mx * [scale] + [tx], my * [scale] + [ty]). [labels] come in the robot's order, one for every room
 * the map has a box for. Sizes and positions are in the units of that view: CSS pixels in the page,
 * where the margins below were chosen, and so density-independent pixels on a phone.
 *
 * Biggest rooms first, so where two names would collide the bigger room keeps its name. Each name is
 * centred on its room's middle but kept inside the room's box, and is shown only when the box is big
 * enough for it and neither a bigger room's name nor [pin] (the last-seen pin and its caption, while
 * the robot is lost) is in the way. The result is in that order, and leaves out the names not shown.
 */
fun layoutLabels(labels: List<LabelSpec>, scale: Double, tx: Double, ty: Double, pin: ScreenRect?): List<PlacedLabel> {
    val s = scale
    val shown = ArrayList<ScreenRect>()
    if (pin != null) shown.add(pin)
    val placed = ArrayList<PlacedLabel>()
    for (l in labels.sortedByDescending { (it.box.x1 - it.box.x0) * (it.box.y1 - it.box.y0) }) {
        val g = l.box
        // The middle of an L-shaped room can lie close to its edge.
        val x = tx + minOf(maxOf(g.cx * s - l.width / 2, g.x0 * s + 2), g.x1 * s - l.width - 2)
        val y = ty + minOf(maxOf(g.cy * s - l.height / 2, g.y0 * s + 2), g.y1 * s - l.height - 2)
        val fits = (g.x1 - g.x0) * s > l.width + 8 && (g.y1 - g.y0) * s > l.height + 4 &&
            shown.none { q -> x < q.x + q.width + 6 && q.x < x + l.width + 6 && y < q.y + q.height + 2 && q.y < y + l.height + 2 }
        if (fits) {
            shown.add(ScreenRect(x, y, l.width, l.height))
            placed.add(PlacedLabel(l.room, x, y))
        }
    }
    return placed
}
