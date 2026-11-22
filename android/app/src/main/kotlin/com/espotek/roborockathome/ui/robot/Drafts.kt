package com.espotek.roborockathome.ui.robot

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import com.espotek.roborockathome.core.model.Robot

/** The rooms picked on one robot, by id, and how many passes to clean them in: the page's selection and pass count. */
@Immutable
internal data class Draft(val rooms: Set<Int>, val passes: Int) {
    /** The picked rooms [robot] still lists, in its order. A plan redrawn by the robot can drop a room. */
    fun roomsOf(robot: Robot): List<Int> = robot.rooms.map { it.id }.filter { it in rooms }

    companion object {
        val Empty = Draft(emptySet(), 1)
    }
}

/** Every robot's draft by robot id, saved across rotation and the process being killed. */
internal val DraftsSaver = Saver<MutableState<Map<String, Draft>>, ArrayList<Any>>(
    save = { state ->
        ArrayList<Any>().apply {
            for ((robot, draft) in state.value) {
                add(robot)
                add(draft.rooms.toIntArray())
                add(draft.passes)
            }
        }
    },
    restore = { saved ->
        mutableStateOf(saved.chunked(3).associate { (robot, rooms, passes) -> robot as String to Draft((rooms as IntArray).toSet(), passes as Int) })
    },
)
