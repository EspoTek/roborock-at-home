package com.espotek.roborockathome.ui.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A finger moving 20 dp each way.
private const val UP = -20.0
private const val DOWN = 20.0
private const val LEFT = -20.0
private const val RIGHT = 20.0

class PlanViewTest {
    // Downstairs' plan (288 x 228 map pixels) in the plan's window on the designer's phone.
    private val frame = PlanFrame(412.0, 448.0, 288, 228)

    @Test
    fun theWholePlanFitsTwelveDpClearOfTheEdgesAndCentred() {
        assertEquals(388.0 / 288, frame.fit, 1e-9)
        val v = frame.fitted()
        assertEquals(frame.fit, v.scale, 1e-9)
        assertEquals(12.0, v.tx, 1e-9)
        assertEquals((448 - 228 * frame.fit) / 2, v.ty, 1e-9)
        // A tall plan in a wide window fits by its height instead.
        val tall = PlanFrame(600.0, 200.0, 100, 400)
        assertEquals(176.0 / 400, tall.fit, 1e-9)
        assertEquals(12.0, tall.fitted().ty, 1e-9)
    }

    @Test
    fun zoomStaysBetweenTheFitShrunkAndTwelveTimesIt() {
        assertEquals(frame.fit * 0.6, frame.clampScale(0.01), 1e-9)
        assertEquals(frame.fit * 12, frame.clampScale(1000.0), 1e-9)
        assertEquals(2.0, frame.clampScale(2.0), 1e-9)
        // However small the fit, the plan can be zoomed to 6 dp a map pixel.
        val huge = PlanFrame(412.0, 448.0, 4000, 4000)
        assertEquals(6.0, huge.clampScale(1000.0), 1e-9)
    }

    @Test
    fun zoomingKeepsTheMiddleOfTheWindowOnTheSameMapPoint() {
        val v = frame.fitted()
        val middle = v.mapX(206.0) to v.mapY(224.0)
        val z = frame.zoomed(v, ZOOM_STEP)
        assertEquals(frame.fit * ZOOM_STEP, z.scale, 1e-9)
        assertEquals(middle.first, z.mapX(206.0), 1e-9)
        assertEquals(middle.second, z.mapY(224.0), 1e-9)
        // Zooming out past the limit stops at it.
        var out = v
        repeat(10) { out = frame.zoomed(out, 1 / ZOOM_STEP) }
        assertEquals(frame.fit * 0.6, out.scale, 1e-9)
    }

    @Test
    fun eachAxisKeepsAPlanBiggerThanTheWindowOverItAndCentresASmallerOne() {
        // Three times fitted, the plan is bigger than the window both ways. Dragged far right and up,
        // its left edge stops at the window's left and its bottom at the window's bottom.
        val zoomed = frame.zoomed(frame.fitted(), 3.0)
        val far = frame.panned(zoomed, 5000.0, -5000.0)
        assertEquals(0.0, far.tx, 1e-9)
        assertEquals(448 - 228 * zoomed.scale, far.ty, 1e-9)
        val near = frame.panned(zoomed, 10.0, 0.0)
        assertEquals(zoomed.tx + 10, near.tx, 1e-9)
        // One step in, it is wider than the window but not as tall: it moves sideways and stays centred
        // top to bottom.
        val step = frame.zoomed(frame.fitted(), ZOOM_STEP)
        assertTrue(288 * step.scale > 412 && 228 * step.scale < 448)
        val dragged = frame.panned(step, -30.0, 100.0)
        assertEquals(step.tx - 30, dragged.tx, 1e-9)
        assertEquals((448 - 228 * step.scale) / 2, dragged.ty, 1e-9)
    }

    @Test
    fun showOnMapPutsThePointAsNearTheMiddleAsTheBoundsAllow() {
        val v = frame.focused(211.5, 58.5, FOCUS_ZOOM)
        assertEquals(frame.fit * FOCUS_ZOOM, v.scale, 1e-9)
        assertEquals(211.5, v.mapX(206.0), 1e-9)
        // So near the top of the plan, its top edge stops at the top of the window.
        assertEquals(0.0, v.ty, 1e-9)
        val middle = frame.focused(144.0, 114.0, FOCUS_ZOOM)
        assertEquals(144.0, middle.mapX(206.0), 1e-9)
        assertEquals(114.0, middle.mapY(224.0), 1e-9)
    }

    @Test
    fun aPinchKeepsTheMapPointUnderTheFingers() {
        val v = frame.fitted()
        val under = v.mapX(150.0) to v.mapY(200.0)
        val p = frame.pinched(v, 150.0, 200.0, 170.0, 190.0, 2.0)
        assertEquals(v.scale * 2, p.scale, 1e-9)
        assertEquals(under.first, p.mapX(170.0), 1e-9)
        assertEquals(under.second, p.mapY(190.0), 1e-9)
    }

    @Test
    fun aDragScrollsThePageAtTheFittedSizeAndPansThePlanOnceZoomedIn() {
        val fitted = frame.fitted()
        assertFalse(frame.planOwnsDrag(fitted, 0.0, UP))
        // Smaller than fitted, and a pinch that ended a hair past it, still scroll the page.
        assertFalse(frame.planOwnsDrag(frame.clamped(fitted.copy(scale = frame.fit * 0.6)), 0.0, UP))
        assertFalse(frame.planOwnsDrag(frame.clamped(fitted.copy(scale = frame.fit * 1.04)), 0.0, UP))
        // Zoomed in about the middle, the plan has room to move every way.
        val zoomed = frame.zoomed(fitted, 3.0)
        for ((dx, dy) in listOf(LEFT to 0.0, RIGHT to 0.0, 0.0 to UP, 0.0 to DOWN)) assertTrue(frame.planOwnsDrag(zoomed, dx, dy))
        // Zooming back out gives the page its drags again.
        assertFalse(frame.planOwnsDrag(frame.zoomed(frame.zoomed(fitted, ZOOM_STEP), 1 / ZOOM_STEP), 0.0, UP))
    }

    @Test
    fun aZoomedPlanAtAnEdgeGivesTheDragsThatWayToThePage() {
        val zoomed = frame.zoomed(frame.fitted(), 3.0)
        // Dragged all the way up, its bottom edge is in the window: up goes to the page, down pans.
        val bottom = frame.panned(zoomed, 0.0, -5000.0)
        assertFalse(frame.planOwnsDrag(bottom, 0.0, UP))
        assertTrue(frame.planOwnsDrag(bottom, 0.0, DOWN))
        val top = frame.panned(zoomed, 0.0, 5000.0)
        assertFalse(frame.planOwnsDrag(top, 0.0, DOWN))
        assertTrue(frame.planOwnsDrag(top, 0.0, UP))
        val leftEdge = frame.panned(zoomed, 5000.0, 0.0)
        assertFalse(frame.planOwnsDrag(leftEdge, RIGHT, 0.0))
        assertTrue(frame.planOwnsDrag(leftEdge, LEFT, 0.0))
        val rightEdge = frame.panned(zoomed, -5000.0, 0.0)
        assertFalse(frame.planOwnsDrag(rightEdge, LEFT, 0.0))
        assertTrue(frame.planOwnsDrag(rightEdge, RIGHT, 0.0))
        // Within 1 dp of the edge is at it.
        assertFalse(frame.planOwnsDrag(bottom.copy(ty = bottom.ty + 0.5), 0.0, UP))
        assertTrue(frame.planOwnsDrag(bottom.copy(ty = bottom.ty + 2.0), 0.0, UP))
        // The drag's main direction decides: mostly sideways, it still pans a plan at its bottom edge.
        assertTrue(frame.planOwnsDrag(bottom, RIGHT, UP / 2))
        // On an axis where the plan is centred it cannot move at all.
        val step = frame.zoomed(frame.fitted(), ZOOM_STEP)
        assertFalse(frame.planOwnsDrag(step, 0.0, UP))
        assertFalse(frame.planOwnsDrag(step, 0.0, DOWN))
        assertTrue(frame.planOwnsDrag(step, LEFT, 0.0))
    }

    @Test
    fun aFingersReachOfTwelveDpIsFewerMapPixelsTheFurtherInTheView() {
        val v = PlanView(2.0, 0.0, 0.0)
        assertEquals(6.0, v.reach, 1e-9)
        assertEquals(12.0 / frame.fit, frame.fitted().reach, 1e-9)
        // The tap point converts to map pixels with the same view.
        assertEquals(20.0, PlanView(2.0, 10.0, 0.0).mapX(50.0), 1e-9)
    }

    @Test
    fun thePlanTakesItsShareOfTheWindowHeight() {
        assertEquals(448, planHeightDp(915))
        assertEquals(313, planHeightDp(640))
        assertEquals(392, planHeightDp(800))
        // A phone on its side is 412 dp tall: the portrait figure does not carry over.
        assertEquals(202, planHeightDp(412))
    }
}
