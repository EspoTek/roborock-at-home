package com.espotek.roborockathome.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.ui.activity.ActivityScreen
import com.espotek.roborockathome.ui.common.BottomBarState
import com.espotek.roborockathome.ui.common.BottomNavBar
import com.espotek.roborockathome.ui.common.Destination
import com.espotek.roborockathome.ui.common.LocalBottomBar
import com.espotek.roborockathome.ui.common.LocalConnectingToServer
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.LocalScheduleRooms
import com.espotek.roborockathome.ui.common.MessageHost
import com.espotek.roborockathome.ui.common.Messages
import com.espotek.roborockathome.ui.common.RoomsToSchedule
import com.espotek.roborockathome.ui.common.rememberMessages
import com.espotek.roborockathome.ui.robot.RobotScreen
import com.espotek.roborockathome.ui.schedules.SchedulesScreen
import com.espotek.roborockathome.ui.settings.SettingsScreen
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahMotion

/**
 * The app's frame once signed in: the chosen destination's screen, short messages above the bottom
 * bar, and the bar itself. On a wide window the screens and messages keep to a centred 600 dp
 * column. Back from another destination returns to Robot, and from Robot leaves the app. The
 * destination, and each destination's saved state, survive rotation and the process being killed.
 * Until the stream is live every screen shows "Connecting to server" under its top bar. Screens reach
 * [messages] through LocalMessages.
 *
 * Each screen shows [home] and acts through [store]. A screen that opens a page of its own, such as
 * a settings subpage, draws it in its own body with its own top bar and Back, and the bottom bar
 * stays; a page that takes the whole screen, the schedule editor, hides the bar with HideBottomBar.
 * Robot opens a new schedule for the rooms picked on it in Schedules through LocalScheduleRooms.
 * Settings shows [serverUrl] and offers [onSignOut], which returns the app to sign-in.
 */
@Composable
fun Shell(
    home: Home,
    store: HomeStore,
    serverUrl: String,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    messages: Messages = rememberMessages(),
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Robot) }
    val screens = rememberSaveableStateHolder()
    BackHandler(enabled = destination != Destination.Robot) { destination = Destination.Robot }
    val bottomBar = remember { BottomBarState() }
    // Rooms the Robot screen asked to schedule, until Schedules has opened its editor on them.
    var toSchedule by remember { mutableStateOf<RoomsToSchedule?>(null) }
    val scheduleRooms = remember {
        { rooms: RoomsToSchedule ->
            toSchedule = rooms
            destination = Destination.Schedules
        }
    }

    // The keyboard rises over the bottom bar; what is under the body already keeps it that far up.
    var belowBody by remember { mutableIntStateOf(0) }
    val belowBodyDp = with(LocalDensity.current) { belowBody.toDp() }

    CompositionLocalProvider(
        LocalMessages provides messages,
        LocalConnectingToServer provides (home.link != Link.Live),
        LocalBottomBar provides bottomBar,
        LocalScheduleRooms provides scheduleRooms,
    ) {
        Column(
            modifier
                .fillMaxSize()
                .background(Rah.colors.page)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(PaddingValues(bottom = belowBodyDp)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Crossfade(
                    targetState = destination,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                    animationSpec = tween(RahMotion.TRANSITION),
                    label = "destination",
                ) { shown ->
                    screens.SaveableStateProvider(shown.name) {
                        when (shown) {
                            Destination.Robot -> RobotScreen(home, store)
                            Destination.Schedules -> SchedulesScreen(home, store, toSchedule, onScheduling = { toSchedule = null })
                            Destination.Activity -> ActivityScreen(home, store)
                            Destination.Settings -> SettingsScreen(home, store, serverUrl, onSignOut)
                        }
                    }
                }
            }
            Column(Modifier.onSizeChanged { belowBody = it.height }) {
                MessageHost(messages, Modifier.widthIn(max = 600.dp).align(Alignment.CenterHorizontally))
                if (bottomBar.hidden) {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                } else {
                    BottomNavBar(destination, onSelect = { destination = it })
                }
            }
        }
    }
}
