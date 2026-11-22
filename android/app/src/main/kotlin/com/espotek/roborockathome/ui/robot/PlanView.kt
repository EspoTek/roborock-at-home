package com.espotek.roborockathome.ui.robot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The page's view of the floor plan, in dp where the page has CSS pixels, so its margins, limits
// and finger reach carry over unchanged.

/** How far a finger may land outside a room and still pick it, and move and still tap. */
internal const val TAP_REACH = 12.0

/** How much the plan zooms in on the last known position for "Show on map". */
internal const val FOCUS_ZOOM = 2.4

/** One press of zoom in; zoom out divides by it. */
internal const val ZOOM_STEP = 1.35

// The plan fits 12 dp clear of every edge of its window.
private const val MARGIN = 12.0

// A view this close to the fitted scale still counts as fitted, so a pinch that ends almost where it
// began gives the page its scrolling back.
private const val FIT_TOLERANCE = 0.05

/** Map pixel (x, y) is drawn at (x * [scale] + [tx], y * [scale] + [ty]) dp in the plan's window. */
internal data class PlanView(val scale: Double, val tx: Double, val ty: Double) {
    /** The map point under window point ([x], [y]). */
    fun mapX(x: Double): Double = (x - tx) / scale
    fun mapY(y: Double): Double = (y - ty) / scale

    /** [TAP_REACH] in map pixels at this scale, for `roomAtOrNull`. */
    val reach: Double get() = TAP_REACH / scale

    internal fun towards(target: PlanView, t: Double) = PlanView(
        scale + (target.scale - scale) * t,
        tx + (target.tx - tx) * t,
        ty + (target.ty - ty) * t,
    )
}

/**
 * A window of [width] x [height] dp onto a plan of [mapW] x [mapH] map pixels, and the views it
 * allows: the page's `computeFit`, `clampS`, `zoomBy` and `focus`, and the bounds the designer set for
 * panning (see [clamped]).
 */
internal data class PlanFrame(val width: Double, val height: Double, val mapW: Int, val mapH: Int) {
    /** The scale at which the whole plan shows. */
    val fit: Double = min((width - 2 * MARGIN) / mapW, (height - 2 * MARGIN) / mapH)

    /** The whole plan, centred. */
    fun fitted(): PlanView = PlanView(fit, (width - mapW * fit) / 2, (height - mapH * fit) / 2)

    /** From a little smaller than fitted to twelve times that, and never less than 6 dp a map pixel at most. */
    fun clampScale(scale: Double): Double = scale.coerceIn(fit * 0.6, max(fit * 12, 6.0))

    /**
     * [view] moved, if it has to be, on each axis separately: where the drawn plan is bigger than the
     * window its edges stay outside the window, and where it is smaller it is centred, so there is
     * never an empty band on one side while the plan is cut off on the other.
     */
    fun clamped(view: PlanView): PlanView =
        PlanView(view.scale, xRange(view.scale).clamp(view.tx), yRange(view.scale).clamp(view.ty))

    /** [view] zoomed by [factor] about the middle of the window, then [clamped]. */
    fun zoomed(view: PlanView, factor: Double): PlanView {
        val s = clampScale(view.scale * factor)
        val x = width / 2
        val y = height / 2
        return clamped(PlanView(s, x - (x - view.tx) * s / view.scale, y - (y - view.ty) * s / view.scale))
    }

    /** Map point ([x], [y]) as near the middle of the window as [clamped] allows, at [factor] times the fitted scale. */
    fun focused(x: Double, y: Double, factor: Double): PlanView {
        val s = clampScale(fit * factor)
        return clamped(PlanView(s, width / 2 - x * s, height / 2 - y * s))
    }

    /** [view] dragged by ([dx], [dy]) dp. */
    fun panned(view: PlanView, dx: Double, dy: Double): PlanView = clamped(view.copy(tx = view.tx + dx, ty = view.ty + dy))

    /**
     * [view] after two fingers have moved: their middle from ([fromX], [fromY]) to ([toX], [toY]) and
     * the distance between them grown by [zoom]. The map point that was under their middle stays under it.
     */
    fun pinched(view: PlanView, fromX: Double, fromY: Double, toX: Double, toY: Double, zoom: Double): PlanView {
        val s = clampScale(view.scale * zoom)
        val wx = view.mapX(fromX)
        val wy = view.mapY(fromY)
        return clamped(PlanView(s, toX - wx * s, toY - wy * s))
    }

    /**
     * Who a one-finger drag that has moved by ([dx], [dy]) belongs to: the page while the plan is at
     * (or near) its fitted size; once it is zoomed in, the plan if it can still move in the drag's main
     * direction, so the drag pans it, and otherwise the page, so a plan already at its edge never
     * traps the page. [view] is [clamped].
     */
    fun planOwnsDrag(view: PlanView, dx: Double, dy: Double): Boolean =
        view.scale > fit * (1 + FIT_TOLERANCE) &&
            if (abs(dx) >= abs(dy)) xRange(view.scale).canMove(view.tx, dx) else yRange(view.scale).canMove(view.ty, dy)

    // Where tx (or ty) may be at [scale]: the drawn plan's edges outside the window, or one centred place.
    private fun xRange(scale: Double) = Offsets(width, mapW * scale)

    private fun yRange(scale: Double) = Offsets(height, mapH * scale)
}

// The offsets a plan [drawn] long may take in a window [window] long, on one axis.
private class Offsets(window: Double, drawn: Double) {
    val low = if (drawn > window) window - drawn else (window - drawn) / 2
    val high = if (drawn > window) 0.0 else low

    fun clamp(t: Double) = t.coerceIn(low, high)

    // Whether the plan can still move with a finger going the way of [d], more than an edge's 1 dp.
    fun canMove(t: Double, d: Double) = if (d > 0) t < high - EDGE else t > low + EDGE
}

// A plan this close to the end of its range has reached its edge.
private const val EDGE = 1.0

/** The plan's height in a window [windowHeightDp] tall: 448 dp of the designer's 915, in proportion. */
internal fun planHeightDp(windowHeightDp: Int): Int = (windowHeightDp * 448 + 457) / 915
