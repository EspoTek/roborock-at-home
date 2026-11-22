package com.espotek.roborockathome.ui.robot

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Who a gesture on the plan belongs to, settled when it first moves further than a tap may. */
internal enum class DragOwner { Undecided, Page, Plan }

// The plan takes part in nested scrolling only to hand the page the drags that are the page's.
private object NotAScrollParent : NestedScrollConnection

/**
 * The plan's gestures, as agreed with the designer. A finger that comes down and lifts within
 * [TAP_REACH] dp of where it landed is a tap there, however long it stayed: [onTap] gets the point,
 * in px. Once a finger has moved further, the drag belongs to the plan when it is zoomed in and
 * can still move that way (it pans), and otherwise to the page (it scrolls, and flings when let go,
 * through [page]); that does not change until every finger has lifted. Two fingers always pinch the
 * plan, and a pinch never turns into a tap when one finger lifts.
 *
 * Every change on the plan is consumed, so the page never starts a scroll of its own from here:
 * only this decides.
 */
internal fun Modifier.planGestures(state: PlanState, page: NestedScrollDispatcher, onTap: (Offset) -> Unit): Modifier =
    nestedScroll(NotAScrollParent, page).pointerInput(state, page) {
        val slop = TAP_REACH.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            state.stop()
            var owner = DragOwner.Undecided
            var travelled = Offset.Zero
            // The page's drag is measured in how far it has scrolled, since the plan moves with it.
            var scrolled = 0f
            val velocity = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent()
                val held = event.changes.filter { it.pressed }
                if (held.isEmpty()) {
                    when (owner) {
                        DragOwner.Undecided -> onTap(down.position)
                        DragOwner.Page -> {
                            val fling = Velocity(0f, velocity.calculateVelocity().y)
                            page.coroutineScope.launch {
                                val taken = page.dispatchPreFling(fling)
                                page.dispatchPostFling(taken, fling - taken)
                            }
                        }
                        DragOwner.Plan -> {}
                    }
                    event.changes.forEach { it.consume() }
                    break
                }
                when (owner) {
                    DragOwner.Undecided ->
                        if (held.size > 1) {
                            owner = DragOwner.Plan
                        } else {
                            travelled += held[0].positionChange()
                            if (travelled.getDistance() > slop) {
                                owner = if (state.planOwnsDrag(travelled.x.toDouble(), travelled.y.toDouble())) DragOwner.Plan else DragOwner.Page
                                velocity.addPosition(held[0].uptimeMillis, Offset.Zero)
                            }
                        }
                    DragOwner.Page -> {
                        val dy = held[0].positionChange().y
                        scrolled += dy
                        velocity.addPosition(held[0].uptimeMillis, Offset(0f, scrolled))
                        val delta = Offset(0f, dy)
                        val taken = page.dispatchPreScroll(delta, NestedScrollSource.UserInput)
                        page.dispatchPostScroll(Offset.Zero, delta - taken, NestedScrollSource.UserInput)
                    }
                    DragOwner.Plan -> movePlan(state, event.changes)
                }
                event.changes.forEach { it.consume() }
            }
        }
    }

// Only fingers that were down before this event and still are move the plan, so one landing or
// lifting never makes it jump.
private fun PointerInputScope.movePlan(state: PlanState, changes: List<PointerInputChange>) {
    val moving = changes.filter { it.pressed && it.previousPressed }
    when {
        moving.size >= 2 -> {
            val (a, b) = moving
            val before = (a.previousPosition - b.previousPosition).getDistance()
            val after = (a.position - b.position).getDistance()
            val zoom = if (before > 0f) (after / before).toDouble() else 1.0
            state.pinch((a.previousPosition + b.previousPosition) / 2f / density, (a.position + b.position) / 2f / density, zoom)
        }
        moving.size == 1 -> {
            val d = moving[0].positionChange() / density
            state.pan(d.x.toDouble(), d.y.toDouble())
        }
    }
}
