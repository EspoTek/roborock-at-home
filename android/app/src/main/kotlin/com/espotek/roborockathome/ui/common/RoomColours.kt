package com.espotek.roborockathome.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.espotek.roborockathome.core.map.FloorPlan
import com.espotek.roborockathome.core.map.RoomPalette
import com.espotek.roborockathome.core.map.SlotColours
import com.espotek.roborockathome.core.map.Tones
import com.espotek.roborockathome.core.map.adjacency
import com.espotek.roborockathome.core.map.assignSlots
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.ui.theme.Rah

/**
 * One robot's room colours in the theme showing: a room has the same colour on the plan, on its
 * chip and in the schedule editor, and the same as on the web page.
 */
@Immutable
class RoomColours internal constructor(
    /** Room id to palette slot, for `paintFloorPlan`. */
    val slots: Map<Int, Int>,
    /** The theme's floor-plan tones, for `paintFloorPlan`. */
    val tones: Tones,
) {
    /** Room [id]'s colours: as drawn, when picked, and the edge of a picked room. */
    fun of(id: Int): SlotColours {
        val slot = checkNotNull(slots[id]) { "Room $id is not one of this robot's rooms, so it has no colour" }
        return tones.slots[slot]
    }
}

/**
 * [robot]'s rooms as chips in its own order, the [picked] ones selected, as the page's chips: the
 * swatch and a selected chip take the room's picked tone. Until [colours] come they are plain.
 * [onToggle] gets a room id.
 */
@Composable
fun RoomChips(robot: Robot, colours: RoomColours?, picked: Set<Int>, onToggle: (Int) -> Unit) {
    val c = Rah.colors
    ChipFlow {
        for (room in robot.rooms) {
            val tone = colours?.of(room.id)
            SwatchChip(
                room.name,
                selected = room.id in picked,
                onClick = { onToggle(room.id) },
                fill = tone?.let { Color(it.selected) } ?: c.surfaceSecondary,
                edge = tone?.let { Color(it.edge) } ?: c.outline,
            )
        }
    }
}

/**
 * [robot]'s room colours, or null while its plan is on its way: colours wait for the plan, like the
 * page's, so they never change under the person. A robot with no map at all has colours at once.
 */
@Composable
fun rememberRoomColours(home: Home, robot: Robot): RoomColours? {
    val dark = Rah.dark
    val rev = robot.map?.rev
    val plan = home.plans[robot.id]?.takeIf { it.rev == rev }
    val ids = robot.rooms.map { it.id }
    return remember(robot.id, rev, plan != null, ids, dark) {
        val tones = if (dark) RoomPalette.dark else RoomPalette.light
        when {
            rev == null -> RoomColours(assignSlots(ids, emptyList(), RoomPalette.distances), tones)
            plan == null -> null
            else -> RoomColours(assignSlots(ids, adjacency(FloorPlan(plan.w, plan.h, plan.bytes)), RoomPalette.distances), tones)
        }
    }
}
