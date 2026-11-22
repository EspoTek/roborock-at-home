package com.espotek.roborockathome.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.model.ActionType
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.ScheduleRequest
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.BorderedCard
import com.espotek.roborockathome.ui.common.EmptyState
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.RoomsToSchedule
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.ToggleSwitch
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah
import java.time.ZoneId

/**
 * Schedules: every robot's schedules as cards, in time order, and the editor as a page of its own.
 * Shows [home] and acts through [store]. The open page and its draft are kept in saved state.
 * [rooms] are rooms the Robot screen has asked to schedule: the editor opens on a new schedule for
 * them, and [onScheduling] says they have been taken.
 */
@Composable
fun SchedulesScreen(home: Home, store: HomeStore, rooms: RoomsToSchedule?, onScheduling: () -> Unit) {
    var editing by rememberSaveable(stateSaver = Editing.Saver) { mutableStateOf<Editing?>(null) }
    val app = home.app
    LaunchedEffect(rooms) {
        if (rooms != null) {
            // Without a time zone no schedule can be made yet, and the list says so.
            if (app?.tz != null) {
                val draft = ScheduleDraft.new(home.robots.first { it.id == rooms.robot })
                    .copy(wholeHome = false, rooms = rooms.rooms.toCollection(LinkedHashSet()), passes = rooms.passes)
                editing = Editing(draft, draft)
            }
            onScheduling()
        }
    }
    // Unlinking the account takes every robot and schedule with it, so there is nothing left to edit.
    val nothingToEdit = app != null && home.robots.isEmpty()
    LaunchedEffect(nothingToEdit) { if (nothingToEdit) editing = null }
    val open = editing
    when {
        // Before the first state there is nothing to show, not even an empty list. A page that was open
        // when the process was killed opens again once the state has come.
        app == null -> ScreenFrame(topBar = { TopBar("Schedules") }) {}
        open != null && !nothingToEdit ->
            ScheduleEditor(home, store, open, onEdit = { editing = it }, onClose = { editing = null })
        else -> ScheduleList(home, store, onOpen = { editing = it })
    }
}

/**
 * The robot a draft for robot [id] is edited for. A schedule whose robot has gone from the account
 * is given the first robot, and loses its rooms on the way (see [ScheduleDraft.fitTo]).
 */
internal fun robotFor(home: Home, id: String): Robot = home.robots.firstOrNull { it.id == id } ?: home.robots.first()

@Composable
private fun ScheduleList(home: Home, store: HomeStore, onOpen: (Editing) -> Unit) {
    val app = checkNotNull(home.app)
    val words = rememberTimeWords()
    val note = when {
        home.robots.isEmpty() -> "Schedules can be made once a robot is linked."
        app.tz == null -> "Set the time zone in Settings before making a schedule."
        else -> null
    }
    ScreenFrame(topBar = { TopBar("Schedules") }) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = Rah.space.gutter, end = Rah.space.gutter, bottom = Rah.space.s16),
                verticalArrangement = Arrangement.spacedBy(Rah.space.s12),
            ) {
                item(key = "about") {
                    if (note != null) {
                        Note(note)
                    } else {
                        Text("Schedules run in ${app.tz} time.", style = Rah.type.supporting, color = Rah.colors.textSupporting)
                    }
                }
                if (home.schedules.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            Glyph.Calendar,
                            "No schedules yet",
                            "Add one and a robot cleans on its own, for example every weekday at ${words.time("09:30")}.",
                        )
                    }
                } else {
                    // Schedules exist only once the server has a time zone to run them in, and their
                    // runs are told on its clock.
                    val zone = ZoneId.of(checkNotNull(app.tz) { "There are schedules but no time zone" })
                    val serverWords = words.inZone(zone)
                    items(home.schedules.sortedBy { it.time }, key = { it.id }) { schedule ->
                        val robot = home.robots.firstOrNull { it.id == schedule.robot }
                        ScheduleCard(
                            schedule, robot, serverWords, store,
                            // With no robot left at all the editor has nothing to offer.
                            canEdit = home.robots.isNotEmpty(),
                            onOpen = {
                                val original = ScheduleDraft.of(schedule)
                                onOpen(Editing(original, original.fitTo(robotFor(home, schedule.robot))))
                            },
                        )
                    }
                }
            }
            // Fixed above the bottom bar, within reach of a thumb, whether the list is empty or long.
            PrimaryButton(
                "New schedule",
                onClick = {
                    val draft = ScheduleDraft.new(home.robots.first())
                    onOpen(Editing(draft, draft))
                },
                glyph = Glyph.Plus,
                enabled = note == null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Rah.space.gutter, vertical = Rah.space.s8),
            )
        }
    }
}

/**
 * One schedule, in the page's order: time and days, then the robot, every room and the passes, then
 * the next run, and the failure of the last one when it failed. A tap on the card edits it; the
 * switch only switches it on or off, pending until the server has answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    schedule: Schedule,
    robot: Robot?,
    words: TimeWords,
    store: HomeStore,
    canEdit: Boolean,
    onOpen: () -> Unit,
) {
    val c = Rah.colors
    val toggle = rememberRunner()
    var asked by remember { mutableStateOf(schedule.enabled) }
    val time = words.time(schedule.time)
    val robotName = robot?.name ?: "A removed robot"
    val lead = if (schedule.enabled) c.text else c.textQuiet
    BorderedCard(onClick = onOpen, onClickLabel = "edit", enabled = canEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Rah.space.s12)) {
                Text(time, style = Rah.type.status, color = lead, modifier = Modifier.alignByBaseline())
                Text(words.days(schedule.days), style = Rah.type.label, color = lead, modifier = Modifier.alignByBaseline())
            }
            ToggleSwitch(
                checked = if (toggle.busy) asked else schedule.enabled,
                onCheckedChange = { on ->
                    asked = on
                    toggle.launch { store.api.updateSchedule(schedule.id, schedule.request(enabled = on)) }
                },
                contentDescription = "$robotName schedule at $time ${words.daysSpoken(schedule.days)}",
                // The line is as tall as the switch is drawn; its 48 dp target reaches into the card's
                // padding instead of pushing the time down from the top edge.
                modifier = Modifier.layout { measurable, constraints ->
                    val target = measurable.measure(constraints)
                    val drawn = minOf(target.height, SWITCH_HEIGHT.roundToPx())
                    layout(target.width, drawn) { target.place(0, (drawn - target.height) / 2) }
                },
                pending = toggle.busy,
            )
        }
        Text(robotName, style = Rah.type.label, color = c.text, modifier = Modifier.padding(top = Rah.space.s8))
        Text(scope(schedule, robot), style = Rah.type.supporting, color = c.textSupporting)
        val next = schedule.nextRun
        Text(
            if (schedule.enabled && next != null) {
                "Next: ${words.moment(next)}" + (words.untilOrNull(next)?.let { " ($it)" } ?: "")
            } else {
                "Off"
            },
            style = Rah.type.supporting,
            color = c.textSupporting,
            modifier = Modifier.padding(top = Rah.space.s4),
        )
        val last = schedule.lastRun
        if (last != null && !last.ok) {
            WarningText(
                "Last run ${words.moment(last.ts)} did not start: ${last.message}",
                Modifier.padding(top = Rah.space.s4),
            )
        }
    }
}

// Material's switch track.
private val SWITCH_HEIGHT = 32.dp

/** The page's `whatText`: "Whole home", or every room by name, then the passes when more than one. */
private fun scope(schedule: Schedule, robot: Robot?): String {
    val action = schedule.action
    if (action.type == ActionType.CleanAll) return "Whole home"
    // A room the robot's map no longer has, or a robot gone from the account, still counts as a room.
    val names = action.rooms.map { id -> robot?.rooms?.firstOrNull { it.id == id }?.name ?: "a room" }
    return names.joinToString(", ") + if (action.repeat > 1) " · ${action.repeat} passes" else ""
}

/** The schedule as it is, switched on or off: the page's `schedBody`, which keeps its name. */
private fun Schedule.request(enabled: Boolean) = ScheduleRequest(robot, name, enabled, days, time, action)

/** A quiet note about why something cannot be done yet, the page's `.note`. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = Rah.type.supporting,
        color = Rah.colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(Rah.colors.surfaceSecondary, Rah.shapes.control)
            .padding(horizontal = Rah.space.s16, vertical = Rah.space.s12),
    )
}
