package com.espotek.roborockathome.ui.robot

import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.Attention
import com.espotek.roborockathome.core.model.AttentionKind
import com.espotek.roborockathome.core.model.Command
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import java.util.Locale

/** The robot does not answer, so it takes no commands; its plan and room picking still work. */
internal val Robot.unreachable: Boolean get() = !online || activity == Activity.Offline

/** The name of room [id] in [robot]'s list, or null for a room it does not list (or no room). */
internal fun roomNameOrNull(robot: Robot, id: Int?): String? = robot.rooms.firstOrNull { it.id == id }?.name

/** A command the action slot offers, with the page's icon and words. */
internal enum class RobotAction(val label: String, val glyph: Glyph, val command: Command) {
    Start("Clean whole home", Glyph.Play, Command.Start),
    Pause("Pause", Glyph.Pause, Command.Pause),
    Resume("Resume", Glyph.Play, Command.Resume),
    Stop("Stop", Glyph.Stop, Command.Stop),
    Dock("Go to dock", Glyph.Dock, Command.Dock),
}

/**
 * What the action slot offers [robot] while no rooms are picked, the main action first: the page's
 * `ACTS`, except that while its battery is low Go to dock leads. A robot that does not answer is
 * offered nothing.
 */
internal fun actionsFor(robot: Robot): List<RobotAction> {
    val actions = when (if (robot.unreachable) Activity.Offline else robot.activity) {
        Activity.Docked -> listOf(RobotAction.Start)
        Activity.Idle -> listOf(RobotAction.Start, RobotAction.Dock)
        Activity.Cleaning -> listOf(RobotAction.Pause, RobotAction.Stop, RobotAction.Dock)
        Activity.Paused -> listOf(RobotAction.Resume, RobotAction.Dock, RobotAction.Stop)
        Activity.Returning -> listOf(RobotAction.Pause, RobotAction.Stop)
        Activity.Error -> listOf(RobotAction.Resume, RobotAction.Dock)
        Activity.Offline -> emptyList()
    }
    val lowBattery = robot.attention?.kind == AttentionKind.LowBattery
    return if (lowBattery && RobotAction.Dock in actions) listOf(RobotAction.Dock) + (actions - RobotAction.Dock) else actions
}

/** The main button while rooms are picked: "Clean 1 room", "Clean 3 rooms". */
internal fun cleanRoomsLabel(count: Int): String = if (count == 1) "Clean 1 room" else "Clean $count rooms"

/** What [robot] is doing in a word or two, as the switcher says it to TalkBack: the page's `actWord`. */
internal fun activityWord(robot: Robot): String =
    if (robot.activity == Activity.Docked && robot.charging == true) {
        "Charging"
    } else {
        when (robot.activity) {
            Activity.Cleaning -> "Cleaning"
            Activity.Returning -> "Going home"
            Activity.Paused -> "Paused"
            Activity.Docked -> "Docked"
            Activity.Idle -> "Idle"
            Activity.Error -> "Needs help"
            Activity.Offline -> "Offline"
        }
    }

/** An area cleaned, to a tenth of a square metre, in the device's number style. */
internal fun area(squareMetres: Double, locale: Locale): String = String.format(locale, "%.1f m²", squareMetres)

/** The caption under the last-seen pin: "Last seen 4:45 PM", with the day when it was not today. */
internal fun TimeWords.lastSeen(ts: Long): String = "Last seen " + (if (date(ts) == today) "" else day(ts) + " ") + time(ts)

/**
 * What the banner says under [attention]'s title, written from its facts in the device's time
 * format rather than taken from the server's sentence. A robot in trouble or no longer answering:
 * "Last seen in Workshop at 4:45 PM with 40% battery." A low battery: "The robot is in Bedroom with
 * 4% battery and is not charging." Each leaves out the room, or the time, that is not known.
 */
internal fun attentionDetail(robot: Robot, attention: Attention, words: TimeWords): String {
    val room = roomNameOrNull(robot, attention.room)
    val battery = "${attention.battery}% battery"
    return when (attention.kind) {
        AttentionKind.Error, AttentionKind.Offline -> {
            val where = room?.let { " in $it" } ?: ""
            val time = robot.pos?.let { " " + words.at(it.ts) } ?: ""
            "Last seen$where$time with $battery."
        }
        AttentionKind.LowBattery -> {
            val charging = if (robot.charging == true) "is charging" else "is not charging"
            if (room != null) "The robot is in $room with $battery and $charging." else "The robot has $battery and $charging."
        }
    }
}
