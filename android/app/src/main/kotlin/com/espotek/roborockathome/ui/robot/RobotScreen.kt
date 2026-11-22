package com.espotek.roborockathome.ui.robot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.model.Command
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.AttentionBanner
import com.espotek.roborockathome.ui.common.BannerAction
import com.espotek.roborockathome.ui.common.EmptyState
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.LocalScheduleRooms
import com.espotek.roborockathome.ui.common.QuietButton
import com.espotek.roborockathome.ui.common.RoomChips
import com.espotek.roborockathome.ui.common.RoomsToSchedule
import com.espotek.roborockathome.ui.common.Runner
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.common.Section
import com.espotek.roborockathome.ui.common.SegmentedControl
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.common.levelLabel
import com.espotek.roborockathome.ui.common.rememberRoomColours
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// After the robot accepts a new level it says so on its next report; the page waits this long for it.
private const val LEVEL_CONFIRM_MS = 10_000L

/**
 * The screen people open the app for: the chosen robot's floor plan, its status, its rooms and
 * levels, and what it can be told to do. It shows [home] and acts through [store]. The robot chosen,
 * and the rooms picked on each robot, survive rotation and the process being killed; if the chosen
 * robot goes away, the first one is chosen.
 */
@Composable
fun RobotScreen(home: Home, store: HomeStore) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var drafts by rememberSaveable(saver = DraftsSaver) { mutableStateOf(emptyMap<String, Draft>()) }
    val robots = home.robots
    if (robots.isEmpty()) {
        NoRobots(home)
        return
    }
    val robot = robots.firstOrNull { it.id == chosen } ?: robots.first()
    SideEffect { chosen = robot.id }
    // Every listed robot keeps its controls, so a request carries on, and shows as busy, through a
    // switch to another robot and back.
    val controls = robots.associate { r -> r.id to key(r.id) { rememberRobotControls() } }
    val latest = rememberUpdatedState(home)
    ScreenFrame(topBar = { RobotSwitcher(robots, robot, onChoose = { chosen = it }) }) {
        key(robot.id) {
            RobotPage(
                home = home,
                robot = robot,
                store = store,
                controls = controls.getValue(robot.id),
                draft = drafts[robot.id] ?: Draft.Empty,
                editDraft = { edit -> drafts = drafts + (robot.id to edit(drafts[robot.id] ?: Draft.Empty)) },
                latest = latest,
            )
        }
    }
}

@Composable
private fun RobotPage(
    home: Home,
    robot: Robot,
    store: HomeStore,
    controls: RobotControls,
    draft: Draft,
    editDraft: ((Draft) -> Draft) -> Unit,
    latest: State<Home>,
) {
    val messages = LocalMessages.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val planState = rememberPlanState()
    val page = remember { NestedScrollDispatcher() }
    val words = rememberTimeWords()
    val colours = rememberRoomColours(home, robot)
    val plan = robot.map?.let { map -> home.plans[robot.id]?.takeIf { it.rev == map.rev } }
    val picked = draft.roomsOf(robot)
    val off = robot.unreachable
    val id = robot.id

    fun toggle(room: Int) = editDraft { d ->
        val now = d.roomsOf(robot).toSet()
        d.copy(rooms = if (room in now) now - room else now + room)
    }

    fun reportedLevel(level: (Robot) -> String?): () -> String? = { latest.value.robots.firstOrNull { it.id == id }?.let(level) }

    // The level shows as chosen and pending from the press until the robot reports it.
    fun pick(runner: Runner, value: String, choose: (String?) -> Unit, reported: () -> String?, command: Command) = runner.launch {
        choose(value)
        try {
            store.api.command(id, command)
            withTimeoutOrNull(LEVEL_CONFIRM_MS) { snapshotFlow(reported).first { it == value } }
        } finally {
            choose(null)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            // The first thing on the page, so on a small screen it scrolls away with the rest.
            val attention = robot.attention
            if (attention != null) {
                val pos = robot.pos
                AttentionBanner(
                    attention.title,
                    attentionDetail(robot, attention, words),
                    action = if (pos != null && plan != null) {
                        BannerAction("Show on map", Glyph.Pin) {
                            scope.launch { scroll.animateScrollTo(0) }
                            planState.focus(pos.x, pos.y)
                        }
                    } else {
                        null
                    },
                )
            }
            FloorPlanArea(
                robot, plan, colours, picked.toSet(), home.paths[id], planState, page, words,
                onPick = ::toggle,
                modifier = Modifier.fillMaxWidth().height(planHeightDp(LocalConfiguration.current.screenHeightDp).dp),
            )
            StatusBlock(
                robot,
                words,
                tools = {
                    MapTools(
                        enabled = plan != null,
                        onZoomOut = { planState.zoomBy(1 / ZOOM_STEP) },
                        onZoomIn = { planState.zoomBy(ZOOM_STEP) },
                        onRecentre = planState::recentre,
                    )
                },
                modifier = Modifier.padding(top = Rah.space.s8),
            )
            Column(
                Modifier.padding(start = Rah.space.gutter, end = Rah.space.gutter, top = Rah.space.s24, bottom = Rah.space.s24),
                verticalArrangement = Arrangement.spacedBy(Rah.space.s24),
            ) {
                if (robot.rooms.isNotEmpty()) {
                    Section("Rooms") {
                        // The chips and the plan are one selection; the chips have no colour until the plan arrives.
                        RoomChips(robot, colours, picked.toSet(), ::toggle)
                        if (picked.isNotEmpty()) {
                            val scheduleRooms = LocalScheduleRooms.current
                            // Its words line up with the chips; the button's padding reaches into the gutter.
                            QuietButton(
                                "Schedule these rooms",
                                onClick = { scheduleRooms(RoomsToSchedule(id, picked, draft.passes)) },
                                modifier = Modifier.offset(x = -Rah.space.s16),
                            )
                        }
                    }
                }
                if (robot.fanOptions.isNotEmpty()) {
                    Level("Suction", robot.fanOptions, controls.fanChoice ?: robot.fan, controls.fan, !off) { value ->
                        pick(controls.fan, value, { controls.fanChoice = it }, reportedLevel { it.fan }, Command.SetFan(value))
                    }
                }
                if (robot.waterOptions.isNotEmpty()) {
                    Level("Mop water", robot.waterOptions, controls.waterChoice ?: robot.water, controls.water, !off) { value ->
                        pick(controls.water, value, { controls.waterChoice = it }, reportedLevel { it.water }, Command.SetWater(value))
                    }
                }
                SecondaryButton(
                    "Locate",
                    onClick = {
                        val name = robot.name
                        controls.locate.launch {
                            store.api.command(id, Command.Locate)
                            messages.success("$name is playing a sound.")
                        }
                    },
                    glyph = Glyph.Locate,
                    enabled = !off,
                    busy = controls.locate.busy,
                )
            }
        }
        ActionSlot(
            robot,
            picked,
            draft.passes,
            controls,
            onAction = { action -> controls.runner(action).launch { store.api.command(id, action.command) } },
            onClear = { editDraft { Draft.Empty } },
            onPasses = { n -> editDraft { it.copy(passes = n) } },
            onCleanRooms = {
                val command = Command.CleanRooms(picked, draft.passes)
                controls.cleanRooms.launch {
                    store.api.command(id, command)
                    // The server took the job, so the draft it was made from is done with.
                    editDraft { Draft.Empty }
                }
            },
        )
    }
}

// Suction or mop water. [shown] is null until the robot first reports a level, and there is
// nothing to show until then.
@Composable
private fun Level(title: String, options: List<String>, shown: String?, runner: Runner, enabled: Boolean, onPick: (String) -> Unit) {
    if (shown == null) return
    Section(title) {
        SegmentedControl(
            options = options,
            selected = shown,
            onSelect = onPick,
            label = ::levelLabel,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            pending = runner.busy,
        )
    }
}

// No robots: before the account is linked, where to link it; after, that none were found.
@Composable
private fun NoRobots(home: Home) {
    ScreenFrame(topBar = { TopBar("Robot") }) {
        // Until the server has said anything, the frame's "Connecting to server" is all there is to say.
        val app = home.app ?: return@ScreenFrame
        val linked = app.account.linked || app.demo
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Rah.space.gutter)) {
            EmptyState(
                Glyph.Robot,
                if (linked) "No robots yet" else "Link your Roborock account",
                if (linked) {
                    "The account is linked, but no robots were found. Robots added in the Roborock app appear here by themselves."
                } else {
                    "Link it in Settings, under Roborock account, and your robots will appear here."
                },
            ) {
                val problem = app.account.problem
                if (linked && problem != null) WarningText(problem)
            }
        }
    }
}
