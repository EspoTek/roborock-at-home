package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.tabular

/**
 * One choice of a few, side by side in one outline: Suction, Mop water. Each option keeps its full
 * label; when they do not fit on one line the control wraps onto more lines instead of shortening
 * them. Give it fillMaxWidth to spread across the column; without it, it takes the width its labels
 * need. Put it in a [Section] to name it. [description] is what TalkBack reads for an
 * option when the label alone would not be clear. While [pending] the control keeps its colours, the
 * chosen option shows a bar, and nothing can be picked.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
    description: (T) -> String = label,
) {
    require(selected in options) { "The selected option $selected is not one of $options" }
    Layout(
        content = {
            for (option in options) {
                val chosen = option == selected
                Segment(
                    text = label(option),
                    on = chosen,
                    pending = pending && chosen,
                    sides = Rah.space.s12,
                    control = Modifier
                        .selectable(selected = chosen, enabled = enabled && !pending, role = Role.RadioButton, onClick = { onSelect(option) })
                        .semantics {
                            contentDescription = description(option)
                            if (pending && chosen) stateDescription = IN_PROGRESS
                        },
                )
            }
        },
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA).segmentFrame(Rah.colors.controlOutline).selectableGroup(),
        measurePolicy = SegmentRows,
    )
}

/**
 * Several choices of a few in one outline, any number of them on, such as the days of a schedule,
 * drawn as [SegmentedControl] is. It takes the whole width and the options share it equally; when
 * their labels no longer fit side by side at 48 dp or more each, they go onto more lines, the longer
 * lines first (seven days go four and three). [description] is what TalkBack reads for an option.
 */
@Composable
fun <T> MultiSegmentedControl(
    options: List<T>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    description: (T) -> String = label,
) {
    Layout(
        content = {
            for (option in options) {
                val on = option in selected
                Segment(
                    text = label(option),
                    on = on,
                    pending = false,
                    // Narrow sides, as the options have their equal share of the width to fill.
                    sides = Rah.space.s4,
                    control = Modifier
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(option) })
                        .semantics { contentDescription = description(option) },
                )
            }
        },
        modifier = modifier.fillMaxWidth().segmentFrame(Rah.colors.controlOutline),
        measurePolicy = EqualRows,
    )
}

/**
 * How many times the robot goes over each room: 1×, 2× or 3×, each a 48 dp target. TalkBack calls
 * the group "Passes" and its choices "One pass", "Two passes" and "Three passes".
 */
@Composable
fun PassSelector(
    passes: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
) = SegmentedControl(
    options = PASSES.keys.toList(),
    selected = passes,
    onSelect = onChange,
    label = { "$it×" },
    modifier = modifier.semantics { contentDescription = "Passes" },
    enabled = enabled,
    pending = pending,
    description = PASSES::getValue,
)

private val PASSES = mapOf(1 to "One pass", 2 to "Two passes", 3 to "Three passes")

// The outline round a segmented control's options, which sit 1 dp inside it.
private fun Modifier.segmentFrame(outline: Color): Modifier =
    border(1.dp, outline, Rah.shapes.control).clip(Rah.shapes.control).padding(1.dp)

// One option: its label, at least 48 dp each way, filled with the accent surface while [on], with a
// bar along its foot while [pending]. [control] is how it is chosen and what TalkBack hears.
@Composable
private fun Segment(text: String, on: Boolean, pending: Boolean, sides: Dp, control: Modifier) {
    val c = Rah.colors
    CompositionLocalProvider(LocalContentColor provides if (on) c.text else c.textSupporting) {
        Box(
            control
                .drawBehind {
                    if (on) {
                        val inset = 2.dp.toPx()
                        drawRoundRect(
                            color = c.accentSurface,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - 2 * inset, size.height - 2 * inset),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                    }
                }
                .defaultMinSize(minWidth = Rah.size.target, minHeight = Rah.size.target)
                .padding(horizontal = sides, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = Rah.type.label.tabular().copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                textAlign = TextAlign.Center,
            )
            if (pending) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                    PendingBar(Modifier.fillMaxWidth().offset(y = 6.dp))
                }
            }
        }
    }
}

// Options go on as few lines as fit, in order, split as evenly as those lines allow (four options
// that need two lines go two and two, not three and one). Each line shares its spare width out
// equally, and every option on a line takes the height of the tallest.
private object SegmentRows : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        check(constraints.hasBoundedWidth) { "A segmented control needs a bounded width" }
        val available = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(available) }
        val lines = evenLines(natural, available)
        val width = if (lines.size == 1) natural.sum().coerceIn(constraints.minWidth, available) else available
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        for (line in lines) {
            val spare = width - line.sumOf { natural[it] }
            val widths = line.mapIndexed { k, i -> natural[i] + spare / line.size + if (k < spare % line.size) 1 else 0 }
            val height = line.indices.maxOf { k -> measurables[line[k]].minIntrinsicHeight(widths[k]) }
            var x = 0
            line.forEachIndexed { k, i ->
                placed += Triple(measurables[i].measure(Constraints.fixed(widths[k], height)), x, y)
                x += widths[k]
            }
            y += height
        }
        return layout(width, y) { for ((p, px, py) in placed) p.place(px, py) }
    }

    private fun evenLines(natural: List<Int>, available: Int): List<List<Int>> {
        for (count in 1..natural.size) {
            val best = splits(natural.indices.toList(), count)
                .filter { lines -> lines.all { line -> line.sumOf { natural[it] } <= available } }
                .minByOrNull { lines -> lines.maxOf { it.size } - lines.minOf { it.size } }
            if (best != null) return best
        }
        error("One option to a line always fits, since each is at most the available width")
    }

    // Every way to cut [items] into [count] runs in order, those with a longer first line first.
    // A control has a handful of options, so there are at most a few dozen.
    private fun splits(items: List<Int>, count: Int): List<List<List<Int>>> =
        if (count == 1) {
            listOf(listOf(items))
        } else {
            (items.size - count + 1 downTo 1).flatMap { first ->
                splits(items.drop(first), count - 1).map { rest -> listOf(items.take(first)) + rest }
            }
        }
}

// The options share the whole width equally on one line while every label fits its share, else on
// as few lines as fit, the longer lines first, each line sharing the whole width.
private object EqualRows : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        check(constraints.hasBoundedWidth) { "A multiple choice segmented control needs a bounded width" }
        val width = constraints.maxWidth
        val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val perLine = (width / widest).coerceIn(1, measurables.size)
        val lines = (measurables.size + perLine - 1) / perLine
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        var next = 0
        for (line in 0 until lines) {
            val count = measurables.size / lines + if (line < measurables.size % lines) 1 else 0
            val items = measurables.subList(next, next + count)
            next += count
            val widths = List(count) { k -> width / count + if (k < width % count) 1 else 0 }
            val height = items.indices.maxOf { k -> items[k].minIntrinsicHeight(widths[k]) }
            var x = 0
            items.forEachIndexed { k, item ->
                placed += Triple(item.measure(Constraints.fixed(widths[k], height)), x, y)
                x += widths[k]
            }
            y += height
        }
        return layout(width, y) { for ((p, px, py) in placed) p.place(px, py) }
    }
}
