package com.espotek.roborockathome.core.map

/** Rooms [a] and [b] (a < b) are neighbours, over [length] pixels of contact. */
data class Contact(val a: Int, val b: Int, val length: Int)

private const val ROOM_IDS = 256 - ROOM_CLASS

/**
 * Which rooms of [plan] touch and by how much, ordered by `a`, then `b`. Rooms are neighbours when a
 * pixel of one lies within 4 pixels of a pixel of the other along a row or a column with only walls
 * or unexplored space between.
 */
fun adjacency(plan: FloorPlan): List<Contact> {
    val w = plan.width
    val h = plan.height
    val n = IntArray(ROOM_IDS * ROOM_IDS)
    fun link(v: Int, u: Int) {
        val a = minOf(v, u) - ROOM_CLASS
        val b = maxOf(v, u) - ROOM_CLASS
        n[a * ROOM_IDS + b]++
    }
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val v = plan.classAt(i)
        if (v < ROOM_CLASS) continue
        var k = 1
        while (k <= 4 && x + k < w) {
            val u = plan.classAt(i + k)
            if (u == v) break
            if (u >= ROOM_CLASS) { link(v, u); break }
            k++
        }
        k = 1
        while (k <= 4 && y + k < h) {
            val u = plan.classAt(i + k * w)
            if (u == v) break
            if (u >= ROOM_CLASS) { link(v, u); break }
            k++
        }
    }
    val contacts = ArrayList<Contact>()
    for (a in 0 until ROOM_IDS) for (b in a + 1 until ROOM_IDS) if (n[a * ROOM_IDS + b] > 0) contacts.add(Contact(a, b, n[a * ROOM_IDS + b]))
    return contacts
}

/**
 * The colour slot of every room in [rooms] (the robot's list, in its order), keyed in id order.
 * [contacts] is the map's [adjacency], or empty while a robot has no map, when the rooms take the
 * slots in id order. [distances] is [RoomPalette.distances].
 *
 * The cost of a colouring: neighbours pay steeply the closer their colours are (a longer shared edge
 * weighs more), rooms one room apart pay a little for looking alike and a lot for sharing a slot, and
 * any two rooms pay for sharing one, so slots are reused only when a home has more rooms than slots.
 * Of several searches, the one whose closest neighbours are furthest apart is kept. Every step is the
 * page's `assignSlots` in the same order, so a room has the same colour in the app and in the browser.
 */
fun assignSlots(rooms: List<Int>, contacts: List<Contact>, distances: SlotDistances): Map<Int, Int> {
    val ids = rooms.sorted()
    val r = ids.size
    val at = HashMap<Int, Int>()
    ids.forEachIndexed { i, id -> at[id] = i }
    val e = Array(r) { DoubleArray(r) }   // edge weight, 0 when not neighbours
    for (c in contacts) {
        val a = at[c.a]
        val b = at[c.b]
        // A corner touching a corner (under 4 pixels) is not a shared wall.
        if (c.length >= 4 && a != null && b != null) {
            e[a][b] = .5 + minOf(c.length, 40) / 40.0
            e[b][a] = e[a][b]
        }
    }
    val near = Array(r) { BooleanArray(r) }   // share a neighbour
    for (i in 0 until r) for (j in 0 until r) if (i != j && e[i][j] == 0.0) {
        for (k in 0 until r) if (e[i][k] != 0.0 && e[k][j] != 0.0) { near[i][j] = true; break }
    }
    // The page works this out for every pair it weighs; it depends only on the two slots.
    val close = Array(distances.size) { s -> DoubleArray(distances.size) { t -> 1 / StrictMath.pow(distances[s, t] + .05, 4.0) } }
    val search = SlotSearch(e, near, close, distances)
    val weight = DoubleArray(r) { i -> e[i].fold(0.0) { p, v -> p + v } }
    val order = (0 until r).sortedWith(compareByDescending<Int> { weight[it] }.thenBy { it })
    // One search per slot: each tries the slots starting from a different one.
    val won = (0 until distances.size).map { search.run(it, order) }.reduce { won, s ->
        if (s.low > won.low + 1e-9 || (s.low > won.low - 1e-9 && s.total < won.total - 1e-9)) s else won
    }
    return ids.indices.associate { ids[it] to won.slot[it] }
}

private class Colouring(val slot: IntArray, val low: Double, val total: Double)

private class SlotSearch(
    private val e: Array<DoubleArray>,
    private val near: Array<BooleanArray>,
    private val close: Array<DoubleArray>,
    private val distances: SlotDistances,
) {
    private val r = e.size
    private val n = distances.size

    private fun pair(i: Int, j: Int, si: Int, sj: Int): Double =
        if (e[i][j] != 0.0) e[i][j] * close[si][sj]
        else (if (near[i][j]) .02 * close[si][sj] else 0.0) + (if (si == sj) (if (near[i][j]) 40.0 else 4.0) else 0.0)

    private fun cost(slot: IntArray, i: Int, s: Int): Double {
        var c = 0.0
        for (j in 0 until r) if (j != i && slot[j] >= 0) c += pair(i, j, s, slot[j])
        return c
    }

    // The cheapest slot for room i and its cost, trying the slots from slot o on.
    private fun best(slot: IntArray, o: Int, i: Int): Pair<Int, Double> {
        var bs = 0
        var bc = Double.POSITIVE_INFINITY
        for (t in 0 until n) {
            val s = (o + t) % n
            val c = cost(slot, i, s)
            if (c < bc - 1e-9) { bc = c; bs = s }
        }
        return bs to bc
    }

    fun run(o: Int, order: List<Int>): Colouring {
        val slot = IntArray(r) { -1 }
        // The best-connected rooms choose first, each taking the slot that costs least so far.
        for (i in order) slot[i] = best(slot, o, i).first
        // Then move one room to another slot, or swap two rooms, for as long as either lowers the cost.
        var moved = true
        var pass = 0
        while (moved && pass < 50) {
            moved = false
            for (i in 0 until r) {
                val (s, c) = best(slot, o, i)
                if (c < cost(slot, i, slot[i]) - 1e-9) { slot[i] = s; moved = true }
            }
            for (i in 0 until r) for (j in i + 1 until r) {
                val si = slot[i]
                val sj = slot[j]
                if (si == sj) continue
                val before = cost(slot, i, si) + cost(slot, j, sj)
                slot[i] = sj; slot[j] = si
                if (cost(slot, i, sj) + cost(slot, j, si) < before - 1e-9) moved = true else { slot[i] = si; slot[j] = sj }
            }
            pass++
        }
        var low = Double.POSITIVE_INFINITY
        var total = 0.0
        for (i in 0 until r) for (j in 0 until i) {
            total += pair(i, j, slot[i], slot[j])
            if (e[i][j] != 0.0) low = minOf(low, distances[slot[i], slot[j]])
        }
        return Colouring(slot, low, total)
    }
}
