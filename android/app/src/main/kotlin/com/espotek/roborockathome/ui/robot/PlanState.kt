package com.espotek.roborockathome.ui.robot

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.espotek.roborockathome.ui.theme.RahMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where one robot's floor plan is looking. It shows the whole plan, centred, until the person zooms
 * or pans, and then their own view for as long as they keep it. A new window size or plan size
 * refits a fitted plan and keeps a person's view over the plan, as the page does on a resize.
 * Recentre goes back to fitted. Lengths are in dp; see [PlanView].
 */
@Stable
internal class PlanState(private val scope: CoroutineScope) {
    /** The plan's window in dp, once it has been laid out. */
    var window: Size? by mutableStateOf(null)

    /** The plan's size in map pixels, once there is a plan to show. */
    var map: IntSize? by mutableStateOf(null)

    // Null while the plan is fitted: the page's M.user is this being set.
    private var own: PlanView? by mutableStateOf(null)
    private var animation: Job? = null

    /** Null until the plan has a window and a size, that is until it is on screen. */
    val frameOrNull: PlanFrame?
        get() {
            val w = window ?: return null
            val m = map ?: return null
            return PlanFrame(w.width.toDouble(), w.height.toDouble(), m.width, m.height)
        }

    /** Null until the plan is on screen. */
    val viewOrNull: PlanView?
        get() {
            val frame = frameOrNull ?: return null
            return own?.let(frame::clamped) ?: frame.fitted()
        }

    private fun frame(): PlanFrame = checkNotNull(frameOrNull) { "The plan is not on screen, so it cannot be moved" }

    private fun view(): PlanView = checkNotNull(viewOrNull) { "The plan is not on screen, so it cannot be moved" }

    /** Whether a one-finger drag that has moved by ([dx], [dy]) pans the plan; otherwise it scrolls the page. */
    fun planOwnsDrag(dx: Double, dy: Double): Boolean = frame().planOwnsDrag(view(), dx, dy)

    fun zoomBy(factor: Double) = animateTo(frame().zoomed(view(), factor), fitted = false)

    fun recentre() = animateTo(frame().fitted(), fitted = true)

    /** Map point ([x], [y]) in the middle of the window, zoomed in on it. */
    fun focus(x: Double, y: Double) = animateTo(frame().focused(x, y, FOCUS_ZOOM), fitted = false)

    /** Moves the plan with a finger, by ([dx], [dy]) dp. */
    fun pan(dx: Double, dy: Double) {
        own = frame().panned(view(), dx, dy)
    }

    /** Moves and zooms the plan with two fingers: see [PlanFrame.pinched]. In dp. */
    fun pinch(from: Offset, to: Offset, zoom: Double) {
        own = frame().pinched(view(), from.x.toDouble(), from.y.toDouble(), to.x.toDouble(), to.y.toDouble(), zoom)
    }

    /** Stops a zoom or recentre on its way, as a finger landing on the plan does. */
    fun stop() {
        animation?.cancel()
        animation = null
    }

    private fun animateTo(target: PlanView, fitted: Boolean) {
        stop()
        val from = view()
        animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(RahMotion.RECENTRE, easing = EaseOutCubic)) { t, _ ->
                own = from.towards(target, t.toDouble())
            }
            own = if (fitted) null else target
        }
    }
}

@Composable
internal fun rememberPlanState(): PlanState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlanState(scope) }
}
