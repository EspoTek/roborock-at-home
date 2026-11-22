package com.espotek.roborockathome.ui.robot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.ui.theme.Rah

/**
 * The Robot screen's top bar. One robot: its name, alone. Several: every name, the [chosen] one
 * outlined, each with a mark for what it is doing (the accent while it works, an amber diamond while
 * it needs a person); they wrap onto more rows rather than scroll, so a robot in trouble is never
 * out of sight. [onChoose] gets a robot id.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RobotSwitcher(robots: List<Robot>, chosen: Robot, onChoose: (String) -> Unit) {
    if (robots.size == 1) {
        Text(
            chosen.name,
            style = Rah.type.pageTitle,
            color = Rah.colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Rah.size.target)
                .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s8)
                .semantics { heading() },
        )
    } else {
        // The robots' own padding keeps their names apart, so four short names fit a 360 dp phone.
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = Rah.space.s8).selectableGroup()) {
            for (robot in robots) RobotTab(robot, robot.id == chosen.id, onClick = { onChoose(robot.id) })
        }
    }
}

@Composable
private fun RobotTab(robot: Robot, selected: Boolean, onClick: () -> Unit) {
    val c = Rah.colors
    val shape = Rah.shapes.chip
    val status = robot.attention?.title ?: activityWord(robot)
    val battery = robot.battery?.let { ", battery $it%" } ?: ""
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (selected) c.surface else Color.Transparent)
            .border(1.dp, if (selected) c.accent else Color.Transparent, shape)
            // The page's selected robot also has a bar of the accent down its leading edge.
            .drawBehind { if (selected) drawRect(c.accent, size = size.copy(width = 2.dp.toPx())) }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${robot.name}, $status$battery" }
            .padding(horizontal = Rah.space.s8, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(robot)
        Text(
            robot.name,
            style = Rah.type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = c.text,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

// The page's marks: a quiet dot, the accent while the robot cleans or heads home, an amber diamond
// while it needs a person.
@Composable
private fun StatusMark(robot: Robot) {
    val c = Rah.colors
    if (robot.attention != null) {
        Box(Modifier.size(7.dp).rotate(45f).clip(RoundedCornerShape(1.dp)).background(c.attention))
    } else {
        val busy = robot.activity == Activity.Cleaning || robot.activity == Activity.Returning
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (busy) c.accent else c.textQuiet))
    }
}
