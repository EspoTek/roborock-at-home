package com.espotek.roborockathome.ui.robot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotLink
import com.espotek.roborockathome.ui.common.IconOnlyButton
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.tabular

// The activities during which the robot reports a job's figures that mean something.
private val JOB = setOf(Activity.Cleaning, Activity.Paused, Activity.Returning)

/**
 * What [robot] is doing, under the plan: its headline with the plan's [tools] beside it, then its
 * battery, how the server reaches it and the room it is cleaning, then the job's figures. A robot
 * whose trouble the banner already names gets no headline, so its facts move up beside the tools.
 */
@Composable
internal fun StatusBlock(robot: Robot, words: TimeWords, tools: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val c = Rah.colors
    val off = robot.unreachable
    val attention = robot.attention
    val headline = when {
        attention != null && (off || robot.activity == Activity.Error || robot.stateLabel == attention.title) -> null
        off -> "Not answering"
        else -> robot.stateLabel
    }
    // A fault the state label does not already name, while the banner is not saying it.
    val fault = robot.error?.label?.takeIf { attention == null && it != robot.stateLabel }
    val locale = LocalConfiguration.current.locales[0]
    val figures = robot.clean?.takeIf { !off && robot.activity in JOB }?.let { job ->
        listOfNotNull(job.areaM2?.let { area(it, locale) to "Cleaned" }, job.seconds?.let { words.duration(it.toLong()) to "So far" })
    }.orEmpty()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Rah.space.s4)) {
        // The tools' icons sit 12 dp inside their targets, so the last one lines up with the gutter.
        Row(Modifier.padding(start = Rah.space.gutter, end = Rah.space.s4), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (headline != null) {
                    Text(
                        headline,
                        style = Rah.type.status,
                        color = c.text,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Facts(robot, words)
                }
            }
            tools()
        }
        Column(Modifier.padding(horizontal = Rah.space.gutter), verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            if (headline != null) Facts(robot, words)
            if (fault != null) WarningText(fault)
            if (figures.isNotEmpty()) Figures(figures)
        }
    }
}

/** Zoom out, zoom in and recentre, 48 dp each, for the plan above. */
@Composable
internal fun MapTools(enabled: Boolean, onZoomOut: () -> Unit, onZoomIn: () -> Unit, onRecentre: () -> Unit) {
    Row {
        IconOnlyButton(Glyph.Minus, contentDescription = "Zoom out", onClick = onZoomOut, enabled = enabled)
        IconOnlyButton(Glyph.Plus, contentDescription = "Zoom in", onClick = onZoomIn, enabled = enabled)
        IconOnlyButton(Glyph.Recentre, contentDescription = "Recentre the map", onClick = onRecentre, enabled = enabled)
    }
}

// Battery, connection, and where the robot is, as the page's status line.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(robot: Robot, words: TimeWords) {
    val c = Rah.colors
    val off = robot.unreachable
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s16),
        verticalArrangement = Arrangement.spacedBy(Rah.space.s4),
    ) {
        val battery = robot.battery
        if (battery != null) {
            val charging = robot.charging == true
            val ink = when {
                battery <= 15 -> c.attention
                charging -> c.accent
                else -> c.textSupporting
            }
            Fact(
                "$battery%",
                ink,
                spoken = "Battery $battery%" + if (charging) ", charging" else "",
                leading = { BatteryIcon(battery, ink) },
                trailing = if (charging) ({ GlyphIcon(Glyph.Bolt, contentDescription = null, size = 18.dp) }) else null,
            )
        }
        val (glyph, link) = when (if (off) RobotLink.None else robot.link) {
            RobotLink.Local -> Glyph.Wifi to "Local network"
            RobotLink.Cloud -> Glyph.Cloud to "Through the cloud"
            RobotLink.None -> Glyph.Offline to "No connection"
        }
        Fact(link, c.textSupporting, leading = { GlyphIcon(glyph, contentDescription = null, size = 18.dp) })
        val seen = robot.seen
        if (off && robot.attention == null && seen != null) Fact("Last heard ${words.ago(seen)}", c.textSupporting)
        val pos = robot.pos
        val room = roomNameOrNull(robot, pos?.room)
        if (pos != null && pos.live && robot.activity == Activity.Cleaning && room != null) Fact("In $room", c.textSupporting)
    }
}

@Composable
private fun Fact(
    text: String,
    ink: Color,
    spoken: String = text,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    CompositionLocalProvider(LocalContentColor provides ink) {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(text, style = Rah.type.supporting.tabular(), color = ink)
            trailing?.invoke()
        }
    }
}

// The page's battery: an outline with a terminal, filled as far as the charge goes.
@Composable
private fun BatteryIcon(percent: Int, ink: Color) {
    Canvas(Modifier.size(width = 24.dp, height = 13.dp)) {
        val u = size.width / 26f
        drawRoundRect(ink, Offset(0.7f * u, 0.7f * u), Size(21.6f * u, 12.6f * u), CornerRadius(3.4f * u), style = Stroke(1.4f * u))
        drawRoundRect(ink, Offset(23.4f * u, 4.6f * u), Size(2.2f * u, 4.8f * u), CornerRadius(1f * u))
        drawRoundRect(ink, Offset(2.8f * u, 2.8f * u), Size(17.4f * u * percent / 100f, 8.4f * u), CornerRadius(1.8f * u))
    }
}

// The job so far: "77.1 m² Cleaned", "16 min So far".
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(figures: List<Pair<String, String>>) {
    val c = Rah.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Rah.space.s24)) {
        for ((value, label) in figures) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
            ) {
                Text(value, style = Rah.type.status, color = c.text, modifier = Modifier.alignByBaseline())
                Text(label, style = Rah.type.supporting, color = c.textSupporting, modifier = Modifier.alignByBaseline())
            }
        }
    }
}
