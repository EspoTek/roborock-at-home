package com.espotek.roborockathome.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

// What a screen can ask of the shell around it: the two things that reach beyond one destination.

/** Whether the shell's bottom bar is hidden; a screen hides it with [HideBottomBar]. */
@Stable
class BottomBarState {
    var hidden: Boolean by mutableStateOf(false)
        internal set
}

/** The shell provides it; reading it anywhere else is a mistake and fails. */
val LocalBottomBar = staticCompositionLocalOf<BottomBarState> { error("No bottom bar here: the shell provides it") }

/**
 * Hides the bottom bar for as long as the caller is on screen, for a page that takes the whole screen
 * and has its own ways out, such as an editor with Back and Save.
 */
@Composable
fun HideBottomBar() {
    val bar = LocalBottomBar.current
    DisposableEffect(bar) {
        bar.hidden = true
        onDispose { bar.hidden = false }
    }
}

/** Rooms picked on the Robot screen to schedule: [robot]'s id, the [rooms] in its order, and the [passes]. */
@Immutable
data class RoomsToSchedule(val robot: String, val rooms: List<Int>, val passes: Int)

/**
 * Opens the Schedules destination's editor on a new schedule for [RoomsToSchedule], as the page's
 * "Schedule these rooms" does. The shell provides it; reading it anywhere else is a mistake and fails.
 */
val LocalScheduleRooms = staticCompositionLocalOf<(RoomsToSchedule) -> Unit> { error("No schedules here: the shell provides them") }
