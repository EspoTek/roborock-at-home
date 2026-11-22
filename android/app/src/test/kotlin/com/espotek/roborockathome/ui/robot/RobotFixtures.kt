package com.espotek.roborockathome.ui.robot

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.ui.shell.Shell

/** The Robot screen as people see it, in the shell with its bottom bar and messages. */
@Composable
fun RobotInShell(home: Home, store: HomeStore) = Shell(home, store, Fixtures.SERVER_URL, onSignOut = {})

const val PLAN = "Floor plan. Tap a room to select it."

/** Picks [robot] in the switcher. */
internal fun SemanticsNodeInteractionsProvider.choose(robot: String) {
    onNode(hasContentDescription("$robot,", substring = true)).performClick()
}

/** Taps the plan where the name of each of [rooms] goes, the plan being fitted. */
internal fun SemanticsNodeInteractionsProvider.tapRooms(robot: Robot, vararg rooms: Int) {
    val plan = onNodeWithContentDescription(PLAN)
    for (id in rooms) {
        val at = roomOnScreen(robot, id)
        plan.performTouchInput { click(at) }
    }
}

/** Where, on the fitted plan, in px from its corner, the name of [robot]'s room [id] goes. */
internal fun SemanticsNodeInteractionsProvider.roomOnScreen(robot: Robot, id: Int): Offset {
    val node = onNodeWithContentDescription(PLAN).fetchSemanticsNode()
    val d = node.layoutInfo.density.density.toDouble()
    val map = checkNotNull(robot.map)
    val view = PlanFrame(node.size.width / d, node.size.height / d, map.w, map.h).fitted()
    val g = map.rooms.getValue(id)
    return Offset(((g.cx * view.scale + view.tx) * d).toFloat(), ((g.cy * view.scale + view.ty) * d).toFloat())
}
