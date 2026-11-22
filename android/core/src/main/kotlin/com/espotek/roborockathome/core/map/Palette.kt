package com.espotek.roborockathome.core.map

import kotlin.math.abs
import kotlin.math.sqrt

/** One colour slot, ARGB: a room as drawn, picked, and the edge of a picked room. */
data class SlotColours(val normal: Int, val selected: Int, val edge: Int)

/** A theme's floor-plan colours, ARGB, and its twelve room slots. */
data class Tones(val wall: Int, val floor: Int, val seen: Int, val slots: List<SlotColours>)

/** The page's `ROOM_PALETTE`: the slots shared by the map and the room controls. */
object RoomPalette {
    val light = Tones(
        wall = rgb(103, 117, 121), floor = rgb(216, 224, 221), seen = rgb(228, 234, 231),
        slots = listOf(
            SlotColours(rgb(224, 161, 160), rgb(216, 144, 143), rgb(126, 54, 56)),
            SlotColours(rgb(250, 195, 165), rgb(242, 178, 143), rgb(122, 60, 20)),
            SlotColours(rgb(206, 175, 125), rgb(195, 160, 100), rgb(105, 73, 1)),
            SlotColours(rgb(211, 214, 157), rgb(198, 200, 132), rgb(83, 84, 5)),
            SlotColours(rgb(155, 192, 147), rgb(137, 180, 127), rgb(47, 93, 36)),
            SlotColours(rgb(159, 225, 201), rgb(134, 213, 186), rgb(5, 94, 73)),
            SlotColours(rgb(120, 195, 199), rgb(90, 183, 188), rgb(5, 91, 95)),
            SlotColours(rgb(158, 219, 249), rgb(132, 206, 241), rgb(7, 88, 116)),
            SlotColours(rgb(153, 182, 229), rgb(134, 167, 222), rgb(45, 79, 134)),
            SlotColours(rgb(208, 202, 254), rgb(193, 185, 248), rgb(80, 68, 130)),
            SlotColours(rgb(202, 165, 210), rgb(191, 148, 201), rgb(104, 60, 113)),
            SlotColours(rgb(248, 189, 216), rgb(239, 171, 203), rgb(119, 54, 87)),
        ),
    )

    val dark = Tones(
        wall = rgb(78, 91, 98), floor = rgb(43, 53, 59), seen = rgb(35, 43, 49),
        slots = listOf(
            SlotColours(rgb(100, 54, 54), rgb(112, 57, 58), rgb(232, 157, 156)),
            SlotColours(rgb(121, 80, 56), rgb(133, 84, 56), rgb(227, 164, 128)),
            SlotColours(rgb(87, 65, 22), rgb(98, 70, 15), rgb(211, 174, 113)),
            SlotColours(rgb(93, 94, 48), rgb(100, 102, 45), rgb(183, 186, 117)),
            SlotColours(rgb(49, 78, 43), rgb(52, 86, 44), rgb(150, 195, 140)),
            SlotColours(rgb(48, 103, 85), rgb(43, 112, 91), rgb(118, 199, 171)),
            SlotColours(rgb(1, 79, 83), rgb(5, 87, 90), rgb(103, 198, 203)),
            SlotColours(rgb(47, 98, 121), rgb(42, 106, 134), rgb(117, 191, 227)),
            SlotColours(rgb(48, 69, 104), rgb(50, 76, 118), rgb(147, 181, 238)),
            SlotColours(rgb(90, 84, 126), rgb(97, 90, 139), rgb(179, 171, 234)),
            SlotColours(rgb(85, 57, 91), rgb(94, 61, 102), rgb(206, 162, 216)),
            SlotColours(rgb(119, 75, 96), rgb(131, 79, 104), rgb(225, 157, 188)),
        ),
    )

    /** The page's `slotD`: how far apart every pair of this palette's slots looks. */
    val distances: SlotDistances = slotDistances(listOf(light, dark).map { t -> t.slots.map { it.normal } })
}

private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** How far apart slots [i] and [j] look, where the average pair of slots is 1. */
class SlotDistances internal constructor(private val d: Array<DoubleArray>) {
    val size: Int get() = d.size

    operator fun get(i: Int, j: Int): Double = d[i][j]
}

// sRGB (0-255) to OKLab, where straight-line distance tracks how different two colours look.
// StrictMath gives the same bits on every JVM and on Android, so the tests vouch for the phone. V8's
// pow and cbrt round some of the palette's values differently in the last bit, which moves the
// distances by up to 4e-15 and the costs in assignSlots by 2e-14 of themselves: far inside the 1e-9
// by which it compares costs, so the slots come out the same.
internal fun lin(c: Int): Double {
    val v = c / 255.0
    return if (v <= .04045) v / 12.92 else StrictMath.pow((v + .055) / 1.055, 2.4)
}

internal fun oklab(argb: Int): DoubleArray {
    val r = lin(argb shr 16 and 0xFF)
    val g = lin(argb shr 8 and 0xFF)
    val b = lin(argb and 0xFF)
    val l = StrictMath.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
    val m = StrictMath.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
    val s = StrictMath.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
    return doubleArrayOf(
        .2104542553 * l + .793617785 * m - .0040720468 * s,
        1.9779984951 * l - 2.428592205 * m + .4505937099 * s,
        .0259040371 * l + .7827717662 * m - .808675766 * s,
    )
}

// How far apart every pair of slots looks: the smaller of the themes' distances, each theme scaled
// so its average pair is 1. One table for both themes, so a room keeps its slot in either.
internal fun slotDistances(themes: List<List<Int>>): SlotDistances {
    val n = themes[0].size
    val d = Array(n) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
    for (cols in themes) {
        val lab = cols.map(::oklab)
        fun dist(i: Int, j: Int) = hypot(lab[i][0] - lab[j][0], lab[i][1] - lab[j][1], lab[i][2] - lab[j][2])
        var sum = 0.0
        for (i in 0 until n) for (j in 0 until i) sum += dist(i, j)
        for (i in 0 until n) for (j in 0 until n) d[i][j] = minOf(d[i][j], dist(i, j) / (sum / (n * (n - 1) / 2)))
    }
    return SlotDistances(d)
}

// V8's Math.hypot, step for step (scaled by the largest value, then summed with Kahan's
// compensation), so the distances come out in the same bits as the page's.
private fun hypot(a: Double, b: Double, c: Double): Double {
    val values = doubleArrayOf(abs(a), abs(b), abs(c))
    val max = values.max()
    if (max == 0.0) return 0.0
    var sum = 0.0
    var compensation = 0.0
    for (v in values) {
        val n = v / max
        val summand = n * n - compensation
        val preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
    }
    return sqrt(sum) * max
}
