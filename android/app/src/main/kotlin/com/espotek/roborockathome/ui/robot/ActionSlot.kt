package com.espotek.roborockathome.ui.robot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.IconOnlyButton
import com.espotek.roborockathome.ui.common.PassSelector
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.Runner
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.common.SecondaryIconButton
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah
import kotlin.math.max

/**
 * One robot's controls that ask something of it, each with its own busy state, and the suction and
 * water levels last picked on it, which show as pending until the robot reports them.
 */
@Stable
internal class RobotControls(
    private val actions: Map<RobotAction, Runner>,
    val cleanRooms: Runner,
    val locate: Runner,
    val fan: Runner,
    val water: Runner,
) {
    fun runner(action: RobotAction): Runner = actions.getValue(action)

    var fanChoice: String? by mutableStateOf(null)
    var waterChoice: String? by mutableStateOf(null)
}

@Composable
internal fun rememberRobotControls(): RobotControls {
    val actions = RobotAction.entries.associateWith { rememberRunner() }
    val cleanRooms = rememberRunner()
    val locate = rememberRunner()
    val fan = rememberRunner()
    val water = rememberRunner()
    return remember { RobotControls(actions, cleanRooms, locate, fan, water) }
}

/**
 * The strip above the bottom bar with what [robot] can be told to do, outside the scrolling page.
 * With no rooms [picked]: the actions for what it is doing, the main one filled. With rooms picked:
 * Clear, "Clean N rooms" and the passes, laid out for the longest label this robot can show, so
 * nothing moves while rooms are picked. A robot that does not answer and has no rooms picked has
 * nothing to offer, and the strip is not there.
 */
@Composable
internal fun ActionSlot(
    robot: Robot,
    picked: List<Int>,
    passes: Int,
    controls: RobotControls,
    onAction: (RobotAction) -> Unit,
    onClear: () -> Unit,
    onPasses: (Int) -> Unit,
    onCleanRooms: () -> Unit,
) {
    val actions = actionsFor(robot)
    if (picked.isEmpty() && actions.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Rah.colors.surface)) {
        Hairline()
        Box(Modifier.padding(horizontal = Rah.space.gutter, vertical = Rah.space.s8)) {
            if (picked.isEmpty()) {
                ActionRow(actions, controls, onAction)
            } else {
                DraftRow(robot, picked.size, passes, controls.cleanRooms, onClear, onPasses, onCleanRooms)
            }
        }
    }
}

// The main action takes what the others leave. They keep their labels when every label fits on
// one line and are icons alone when not, as on the page's narrow screens.
@Composable
private fun ActionRow(actions: List<RobotAction>, controls: RobotControls, onAction: (RobotAction) -> Unit) {
    val main = actions.first()
    val others = actions.drop(1)
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val gap = Rah.space.s8.roundToPx()
        val natural = subcompose("probe") {
            for (a in actions) Box(Modifier.clearAndSetSemantics {}) { SecondaryButton(a.label, {}, glyph = a.glyph) }
        }.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val labelled = natural.sum() + gap * others.size <= width
        val shown = subcompose(if (labelled) "labelled" else "icons") {
            for (a in others) {
                val runner = controls.runner(a)
                if (labelled) {
                    SecondaryButton(a.label, { onAction(a) }, glyph = a.glyph, busy = runner.busy)
                } else {
                    SecondaryIconButton(a.glyph, a.label, { onAction(a) }, busy = runner.busy)
                }
            }
        }.mapIndexed { i, m -> m.measure(if (labelled) Constraints.fixedWidth(natural[i + 1]) else Constraints()) }
        val mainWidth = width - shown.sumOf { it.width } - gap * others.size
        val first = subcompose("main") {
            PrimaryButton(main.label, { onAction(main) }, glyph = main.glyph, busy = controls.runner(main).busy)
        }.single().measure(Constraints.fixedWidth(mainWidth))
        val height = max(first.height, shown.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            first.place(0, (height - first.height) / 2)
            var x = first.width + gap
            for (p in shown) {
                p.place(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

// Clear, the main button and the passes on one row, the main button's label wrapping there if it
// has to, as long as no word of it has to break; otherwise the main button gets a row of its own
// above the other two. The row is as tall as the longest "Clean N rooms" this robot can show needs,
// so nothing moves while rooms are picked.
@Composable
private fun DraftRow(
    robot: Robot,
    count: Int,
    passes: Int,
    runner: Runner,
    onClear: () -> Unit,
    onPasses: (Int) -> Unit,
    onCleanRooms: () -> Unit,
) {
    val off = robot.unreachable
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val gap = Rah.space.s8.roundToPx()
        val clear = subcompose("clear") {
            IconOnlyButton(Glyph.Close, contentDescription = "Clear the selection", onClick = onClear)
        }.single().measure(Constraints())
        val selector = subcompose("passes") { PassSelector(passes, onPasses) }.single().measure(Constraints(maxWidth = width))
        val longest = subcompose("probe") {
            Box(Modifier.clearAndSetSemantics {}) { PrimaryButton(cleanRoomsLabel(robot.rooms.size), {}) }
        }.single()
        val beside = width - clear.width - selector.width - 2 * gap
        val oneRow = longest.minIntrinsicWidth(Constraints.Infinity) <= beside
        val mainWidth = if (oneRow) beside else width
        val tallest = longest.measure(Constraints.fixedWidth(mainWidth)).height
        val main = subcompose("main") {
            PrimaryButton(cleanRoomsLabel(count), onCleanRooms, enabled = !off, busy = runner.busy)
        }.single().measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth, minHeight = tallest))
        if (oneRow) {
            val height = maxOf(clear.height, main.height, selector.height)
            layout(width, height) {
                clear.place(0, (height - clear.height) / 2)
                main.place(clear.width + gap, (height - main.height) / 2)
                selector.place(width - selector.width, (height - selector.height) / 2)
            }
        } else {
            val second = max(clear.height, selector.height)
            layout(width, main.height + gap + second) {
                main.place(0, 0)
                val y = main.height + gap
                clear.place(0, y + (second - clear.height) / 2)
                selector.place(width - selector.width, y + (second - selector.height) / 2)
            }
        }
    }
}
