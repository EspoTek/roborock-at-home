package com.espotek.roborockathome.ui.schedules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.ConfirmDialog
import com.espotek.roborockathome.ui.common.DangerButton
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.HideBottomBar
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.MultiSegmentedControl
import com.espotek.roborockathome.ui.common.PassSelector
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.QuietButton
import com.espotek.roborockathome.ui.common.RoomChips
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.common.Section
import com.espotek.roborockathome.ui.common.SegmentedControl
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.levelLabel
import com.espotek.roborockathome.ui.common.rememberRoomColours
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah

/** The questions the editor can ask over itself. */
private enum class Ask { Discard, Delete }

/**
 * The schedule editor, a page of its own inside Schedules that takes the whole screen, with the
 * bottom bar hidden: Back and Save in its top bar, then the robot, time, days, rooms and levels,
 * and for a saved schedule Run now and Delete. The list keeps [editing] in saved state; every change
 * comes back through [onEdit], and [onClose] returns to the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleEditor(
    home: Home,
    store: HomeStore,
    editing: Editing,
    onEdit: (Editing) -> Unit,
    onClose: () -> Unit,
) {
    val zone = checkNotNull(checkNotNull(home.app).tz) { "Schedules are edited only once the server has a time zone" }
    HideBottomBar()
    val words = rememberTimeWords()
    val messages = LocalMessages.current
    val robot = robotFor(home, editing.draft.robot)
    // Fitted afresh each time, so a robot whose rooms or levels change meanwhile never shows a choice it lacks.
    val draft = editing.draft.fitTo(robot)
    val changed = draft.changedFrom(editing.original)

    // The save's refusal, shown under the top bar until the draft changes or Save is pressed again.
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf<Ask?>(null) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val save = rememberRunner()
    val run = rememberRunner()
    val delete = rememberRunner()

    fun edit(change: ScheduleDraft) {
        error = null
        onEdit(editing.copy(draft = change))
    }
    val back = { if (changed) asking = Ask.Discard else onClose() }
    BackHandler(onBack = back)

    ScreenFrame(
        topBar = {
            TopBar(
                if (editing.isNew) "New schedule" else "Edit schedule",
                onBack = back,
                actions = {
                    PrimaryButton(
                        "Save",
                        busy = save.busy,
                        onClick = {
                            error = draft.problemOrNull()
                            val id = draft.id
                            if (error == null) {
                                save.launch(onError = { error = it }) {
                                    if (id == null) {
                                        val added = store.api.createSchedule(draft.toRequest())
                                        messages.success("Added: ${words.days(added.days)} at ${words.time(added.time)}")
                                    } else {
                                        store.api.updateSchedule(id, draft.toRequest())
                                        messages.success("Schedule saved")
                                    }
                                    onClose()
                                }
                            }
                        },
                    )
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            error?.let { ErrorText(it, Modifier.padding(horizontal = Rah.space.gutter, vertical = Rah.space.s8)) }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = Rah.space.gutter, end = Rah.space.gutter, top = Rah.space.s8, bottom = Rah.space.s32),
                verticalArrangement = Arrangement.spacedBy(Rah.space.s24),
            ) {
                if (home.robots.size > 1) {
                    Section("Robot") {
                        SegmentedControl(
                            options = home.robots,
                            selected = robot,
                            onSelect = { edit(draft.fitTo(it)) },
                            label = { it.name },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Section("Time") {
                    val time = words.time(draft.time)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
                        verticalArrangement = Arrangement.spacedBy(Rah.space.s4),
                    ) {
                        SecondaryButton(
                            time,
                            onClick = { pickingTime = true },
                            glyph = Glyph.Clock,
                            modifier = Modifier.semantics { contentDescription = "Time, $time" },
                        )
                        Text(
                            "$zone time",
                            style = Rah.type.supporting,
                            color = Rah.colors.textSupporting,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                Section("Days") {
                    MultiSegmentedControl(
                        options = (0..6).toList(),
                        selected = draft.days,
                        onToggle = { edit(draft.copy(days = draft.days.toggled(it))) },
                        label = { words.dayShort(it) },
                        description = { words.dayFull(it) },
                    )
                    // Their words line up with the headings; the buttons' padding reaches into the gutter.
                    FlowRow(Modifier.offset(x = -Rah.space.s16)) {
                        for (shortcut in DayShortcut.entries) {
                            QuietButton(shortcut.label, onClick = { edit(draft.copy(days = shortcut.days)) })
                        }
                    }
                }
                Section("What") {
                    SegmentedControl(
                        options = listOf(true, false),
                        selected = draft.wholeHome,
                        onSelect = { edit(draft.copy(wholeHome = it)) },
                        label = { if (it) "Whole home" else "Pick rooms" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!draft.wholeHome) Rooms(home, robot, draft, ::edit)
                }
                if (robot.fanOptions.isNotEmpty()) {
                    Section("Suction") {
                        Levels(robot.fanOptions, draft.fan, onSelect = { edit(draft.copy(fan = it)) }, leave = "Leave suction as it is")
                    }
                }
                if (robot.waterOptions.isNotEmpty()) {
                    Section("Mop water") {
                        Levels(robot.waterOptions, draft.water, onSelect = { edit(draft.copy(water = it)) }, leave = "Leave mop water as it is")
                    }
                }
                val id = editing.original.id
                if (id != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Rah.space.s24)) {
                        Hairline()
                        // Runs the schedule as it is saved, so not while the page holds changes to it.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
                            verticalArrangement = Arrangement.spacedBy(Rah.space.s4),
                        ) {
                            SecondaryButton(
                                "Run now",
                                onClick = { run.launch { store.api.runSchedule(id); messages.success("Started") } },
                                glyph = Glyph.Play,
                                enabled = !changed,
                                busy = run.busy,
                            )
                            if (changed) {
                                Text(
                                    "Save changes to run this schedule",
                                    style = Rah.type.supporting,
                                    color = Rah.colors.textSupporting,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                        DangerButton("Delete", onClick = { asking = Ask.Delete }, glyph = Glyph.Trash, busy = delete.busy)
                    }
                }
            }
        }
    }

    if (pickingTime) {
        TimeDialog(
            draft.time,
            zone,
            words.use24Hour,
            onPick = {
                pickingTime = false
                edit(draft.copy(time = it))
            },
            onDismiss = { pickingTime = false },
        )
    }
    when (asking) {
        Ask.Discard -> ConfirmDialog(
            title = if (editing.isNew) "Discard this schedule?" else "Discard your changes?",
            text = if (editing.isNew) "The new schedule has not been added." else "Your changes to this schedule have not been saved.",
            confirmLabel = "Discard",
            onConfirm = {
                asking = null
                onClose()
            },
            onDismiss = { asking = null },
        )
        Ask.Delete -> {
            val saved = editing.original
            ConfirmDialog(
                title = "Delete this schedule?",
                text = "${words.days(saved.days)} at ${words.time(saved.time)}. This cannot be undone.",
                confirmLabel = "Delete",
                onConfirm = {
                    asking = null
                    val id = checkNotNull(saved.id) { "Only a saved schedule offers Delete" }
                    delete.launch {
                        store.api.deleteSchedule(id)
                        messages.success("Schedule deleted")
                        onClose()
                    }
                },
                onDismiss = { asking = null },
            )
        }
        null -> {}
    }
}

/** The robot's rooms as chips in its own colours, and the passes, for Pick rooms. */
@Composable
private fun Rooms(home: Home, robot: Robot, draft: ScheduleDraft, edit: (ScheduleDraft) -> Unit) {
    if (robot.rooms.isEmpty()) {
        Text("${robot.name} has not shared its rooms yet.", style = Rah.type.supporting, color = Rah.colors.textSupporting)
        return
    }
    RoomChips(robot, rememberRoomColours(home, robot), draft.rooms, onToggle = { edit(draft.copy(rooms = draft.rooms.toggled(it))) })
    Row(horizontalArrangement = Arrangement.spacedBy(Rah.space.s16), verticalAlignment = Alignment.CenterVertically) {
        // The selector itself tells TalkBack it is the passes.
        Text("Passes", style = Rah.type.label, color = Rah.colors.text, modifier = Modifier.clearAndSetSemantics {})
        PassSelector(draft.passes, onChange = { edit(draft.copy(passes = it)) })
    }
}

/** Suction or Mop water: as it is, or one of the robot's levels. */
@Composable
private fun Levels(options: List<String>, selected: String?, onSelect: (String?) -> Unit, leave: String) {
    SegmentedControl(
        options = listOf(null) + options,
        selected = selected,
        onSelect = onSelect,
        label = { it?.let(::levelLabel) ?: "As it is" },
        modifier = Modifier.fillMaxWidth(),
        description = { it?.let(::levelLabel) ?: leave },
    )
}
