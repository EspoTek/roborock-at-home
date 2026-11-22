package com.espotek.roborockathome.ui.schedules

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import com.espotek.roborockathome.core.model.ActionType
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.ScheduleAction
import com.espotek.roborockathome.core.model.ScheduleRequest

/**
 * A schedule as the editor holds it while it is being changed. [rooms] and [passes] stay while Whole
 * home is chosen, so going back to Pick rooms finds them again; the request leaves them out. [days]
 * are 0 = Monday to 6 = Sunday, [time] is `HH:MM` in the server's time zone.
 */
@Immutable
internal data class ScheduleDraft(
    /** Null for a schedule that does not exist yet. */
    val id: String?,
    val robot: String,
    val enabled: Boolean,
    val wholeHome: Boolean,
    val rooms: Set<Int>,
    val passes: Int,
    val days: Set<Int>,
    val time: String,
    /** A suction level of the robot's, or null to leave its suction as it is. */
    val fan: String?,
    val water: String?,
) {
    /**
     * What the server is sent. Rooms and days go in order, and there is no name, as the page sends
     * it: the server names the schedule from its rooms and days.
     */
    fun toRequest() = ScheduleRequest(
        robot = robot,
        name = null,
        enabled = enabled,
        days = days.sorted(),
        time = time,
        action = if (wholeHome) {
            ScheduleAction(ActionType.CleanAll, rooms = emptyList(), repeat = 1, fan = fan, water = water)
        } else {
            ScheduleAction(ActionType.CleanRooms, rooms = rooms.sorted(), repeat = passes, fan = fan, water = water)
        },
    )

    /** Whether saving would change anything: what counts is what would be sent, not how the form got there. */
    fun changedFrom(original: ScheduleDraft): Boolean = toRequest() != original.toRequest()

    /**
     * The draft for [robot]. A room id means a room on one robot's map only (16 is the Living room on
     * one robot and the Main bedroom on another), so another robot's rooms are all dropped, and the same
     * robot's are kept while its map still has them. Levels [robot] does not have go back to "as it is".
     */
    fun fitTo(robot: Robot): ScheduleDraft {
        val ids = robot.rooms.mapTo(HashSet()) { it.id }
        return copy(
            robot = robot.id,
            rooms = if (robot.id == this.robot) rooms.filterTo(LinkedHashSet()) { it in ids } else emptySet(),
            fan = fan?.takeIf { it in robot.fanOptions },
            water = water?.takeIf { it in robot.waterOptions },
        )
    }

    /** The sentence that stops a save before it is sent, or null when the draft can be sent. */
    fun problemOrNull(): String? = when {
        days.isEmpty() -> "Pick at least one day."
        !wholeHome && rooms.isEmpty() -> "Pick at least one room, or choose Whole home."
        else -> null
    }

    companion object {
        /** A new schedule for [robot], as the page starts one: the whole home on weekdays at 09:30. */
        fun new(robot: Robot) = ScheduleDraft(
            id = null,
            robot = robot.id,
            enabled = true,
            wholeHome = true,
            rooms = emptySet(),
            passes = 1,
            days = DayShortcut.Weekdays.days,
            time = "09:30",
            fan = null,
            water = null,
        )

        /** [schedule] as it is saved. */
        fun of(schedule: Schedule): ScheduleDraft {
            val action = schedule.action
            val rooms = action.type == ActionType.CleanRooms
            return ScheduleDraft(
                id = schedule.id,
                robot = schedule.robot,
                enabled = schedule.enabled,
                wholeHome = !rooms,
                rooms = if (rooms) action.rooms.toCollection(LinkedHashSet()) else emptySet(),
                passes = if (rooms) action.repeat else 1,
                days = schedule.days.toSet(),
                time = schedule.time,
                fan = action.fan,
                water = action.water,
            )
        }

        fun restore(saved: List<Any?>) = ScheduleDraft(
            id = saved[0] as String?,
            robot = saved[1] as String,
            enabled = saved[2] as Boolean,
            wholeHome = saved[3] as Boolean,
            rooms = (saved[4] as IntArray).toCollection(LinkedHashSet()),
            passes = saved[5] as Int,
            days = (saved[6] as IntArray).toSet(),
            time = saved[7] as String,
            fan = saved[8] as String?,
            water = saved[9] as String?,
        )
    }

    /** The draft in a form the platform's saved state can hold; [restore] reads it back. */
    fun saved(): ArrayList<Any?> = arrayListOf(
        id, robot, enabled, wholeHome, rooms.toIntArray(), passes, days.toIntArray(), time, fan, water,
    )
}

/**
 * The editor's page: the schedule as it was when the page opened, and the draft. Saved with the
 * platform's state, so an edit survives rotation and the process being killed.
 */
@Immutable
internal data class Editing(val original: ScheduleDraft, val draft: ScheduleDraft) {
    val isNew: Boolean get() = original.id == null

    companion object {
        // No page open is not saved at all, and so comes back as no page open.
        val Saver = Saver<Editing?, ArrayList<Any?>>(
            save = { editing -> editing?.let { arrayListOf(it.original.saved(), it.draft.saved()) } },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                Editing(ScheduleDraft.restore(saved[0] as List<Any?>), ScheduleDraft.restore(saved[1] as List<Any?>))
            },
        )
    }
}

/** The page's shortcuts under the days. */
internal enum class DayShortcut(val label: String, val days: Set<Int>) {
    EveryDay("Every day", (0..6).toSet()),
    Weekdays("Weekdays", (0..4).toSet()),
    Weekends("Weekends", setOf(5, 6)),
}

/** [days] with [day] switched on or off. */
internal fun Set<Int>.toggled(day: Int): Set<Int> = if (day in this) this - day else this + day
