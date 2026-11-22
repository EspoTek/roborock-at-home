package com.espotek.roborockathome.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.espotek.roborockathome.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.common.AttentionBanner
import com.espotek.roborockathome.ui.common.BannerAction
import com.espotek.roborockathome.ui.common.BorderedCard
import com.espotek.roborockathome.ui.common.BottomNavBar
import com.espotek.roborockathome.ui.common.ChipFlow
import com.espotek.roborockathome.ui.common.ConfirmDialog
import com.espotek.roborockathome.ui.common.ConnectionStrip
import com.espotek.roborockathome.ui.common.DangerButton
import com.espotek.roborockathome.ui.common.Destination
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.IconOnlyButton
import com.espotek.roborockathome.ui.common.IndexRow
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.MessageHost
import com.espotek.roborockathome.ui.common.Messages
import com.espotek.roborockathome.ui.common.MultiSegmentedControl
import com.espotek.roborockathome.ui.common.PassSelector
import com.espotek.roborockathome.ui.common.PasswordField
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.QuietButton
import com.espotek.roborockathome.ui.common.RadioGroup
import com.espotek.roborockathome.ui.common.RadioRow
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.common.SecondaryIconButton
import com.espotek.roborockathome.ui.common.SectionHeading
import com.espotek.roborockathome.ui.common.SegmentedControl
import com.espotek.roborockathome.ui.common.SwatchChip
import com.espotek.roborockathome.ui.common.SwitchRow
import com.espotek.roborockathome.ui.common.ToggleSwitch
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.tabular

/** Components tagged with this are held pressed by the screenshot tests. */
const val PRESS = "press"

/** The text field tagged with this is focused by the screenshot tests. */
const val FOCUS = "focus"

/** The gallery, one page per family of components, every one in its states. Each fits a phone at normal text. */
enum class GalleryPage(val title: String, val content: @Composable () -> Unit) {
    Buttons("Buttons", { ButtonsPage() }),
    Icons("Icons", { IconsPage() }),
    Choices("Choices", { ChoicesPage() }),
    Switches("Switches", { SwitchesPage() }),
    SettingsIndex("Settings index", { SettingsIndexPage() }),
    Fields("Fields", { FieldsPage() }),
    Feedback("Feedback", { FeedbackPage() }),
    Frame("Frame", { FramePage() }),
    BottomBar("Bottom bar", { BottomBarPage() }),
}

@Composable
fun GalleryPageFrame(page: GalleryPage) {
    Column(Modifier.fillMaxWidth().padding(vertical = Rah.space.s16), verticalArrangement = Arrangement.spacedBy(Rah.space.s16)) {
        Text(page.title, style = Rah.type.pageTitle, modifier = Modifier.padding(horizontal = Rah.space.gutter))
        page.content()
    }
}

/** A labelled group of examples, with the screen's gutter. */
@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Rah.space.gutter), verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
        Text(label, style = Rah.type.supporting, color = Rah.colors.textQuiet)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Wrap(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalArrangement = Arrangement.spacedBy(Rah.space.s8),
    ) { content() }
}

@Composable
private fun ButtonsPage() {
    Group("Primary: normal, pressed, busy, disabled") {
        Wrap {
            PrimaryButton("Clean whole home", {}, glyph = Glyph.Play)
            PrimaryButton("Save", {}, Modifier.testTag(PRESS))
            PrimaryButton("Clean 2 rooms", {}, busy = true)
            PrimaryButton("Pause", {}, glyph = Glyph.Pause, enabled = false)
        }
    }
    Group("Secondary: normal, pressed, busy, disabled") {
        Wrap {
            SecondaryButton("Locate", {}, glyph = Glyph.Locate)
            SecondaryButton("Run now", {}, Modifier.testTag(PRESS), glyph = Glyph.Play)
            SecondaryButton("Go to dock", {}, glyph = Glyph.Dock, busy = true)
            SecondaryButton("Copy", {}, glyph = Glyph.Copy, enabled = false)
        }
    }
    Group("Secondary as its icon alone, where labels do not fit: normal, pressed, busy") {
        Wrap {
            SecondaryIconButton(Glyph.Dock, "Go to dock", {})
            SecondaryIconButton(Glyph.Stop, "Stop", {}, Modifier.testTag(PRESS))
            SecondaryIconButton(Glyph.Pause, "Pause", {}, busy = true)
        }
    }
    Group("Quiet: normal, pressed, busy, disabled") {
        Wrap {
            QuietButton("Schedule these rooms", {})
            QuietButton("Cancel", {}, Modifier.testTag(PRESS))
            QuietButton("Connect", {}, busy = true)
            QuietButton("Add person", {}, glyph = Glyph.Plus, enabled = false)
        }
    }
    Group("Danger: normal, pressed, busy, disabled") {
        Wrap {
            DangerButton("Delete", {}, glyph = Glyph.Trash)
            DangerButton("Sign out", {}, Modifier.testTag(PRESS), glyph = Glyph.SignOut)
            DangerButton("Unlink account", {}, busy = true)
            DangerButton("Remove", {}, enabled = false)
        }
    }
}

@Composable
private fun IconsPage() {
    Group("Icon only: zoom out, zoom in, recentre, pressed, disabled, danger") {
        Row {
            IconOnlyButton(Glyph.Minus, "Zoom out", {})
            IconOnlyButton(Glyph.Plus, "Zoom in", {})
            IconOnlyButton(Glyph.Recentre, "Recentre the map", {})
            IconOnlyButton(Glyph.Close, "Clear the selection", {}, Modifier.testTag(PRESS))
            IconOnlyButton(Glyph.Plus, "Zoom in", {}, enabled = false)
            IconOnlyButton(Glyph.Trash, "Remove Anna", {}, tint = Rah.colors.attention)
        }
    }
    Group("Launcher icon in a circle mask, a rounded-square mask, and themed (monochrome)") {
        Row(horizontalArrangement = Arrangement.spacedBy(Rah.space.s16)) {
            val teal = colorResource(R.color.launcher_background)
            val foreground = painterResource(R.drawable.ic_launcher_foreground)
            // A launcher shows the middle 72 of the 108 dp layers.
            for (mask in listOf(CircleShape, RoundedCornerShape(16.dp))) {
                Box(Modifier.size(72.dp).clip(mask).background(teal), contentAlignment = Alignment.Center) {
                    Image(foreground, null, Modifier.requiredSize(108.dp))
                }
            }
            Box(Modifier.size(72.dp).clip(CircleShape).background(Rah.colors.accentSurface), contentAlignment = Alignment.Center) {
                Image(foreground, null, Modifier.requiredSize(108.dp), colorFilter = ColorFilter.tint(Rah.colors.accent))
            }
        }
    }
    Group("Every glyph, by its name") {
        Wrap {
            for (glyph in Glyph.entries) {
                Column(
                    Modifier.widthIn(min = 84.dp).padding(vertical = Rah.space.s4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Rah.space.s4),
                ) {
                    GlyphIcon(glyph, contentDescription = null)
                    Text(glyph.name, style = Rah.type.supporting, color = Rah.colors.textSupporting)
                }
            }
        }
    }
}

private val SUCTION = listOf("Quiet", "Balanced", "Turbo", "Max", "Max+")
private val MOP = listOf("Off", "Low", "Medium", "High", "Customised by room", "Smart plan")
private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun ChoicesPage() {
    Group("Segmented: chosen, pressed (Turbo), pending, disabled") {
        var suction by remember { mutableStateOf("Balanced") }
        SegmentedControl(SUCTION, suction, { suction = it }, { it }, Modifier.fillMaxWidth())
        SegmentedControl(SUCTION, "Balanced", {}, { it }, Modifier.fillMaxWidth().testTag(PRESS))
        SegmentedControl(SUCTION, "Max", {}, { it }, Modifier.fillMaxWidth(), pending = true)
        SegmentedControl(SUCTION, "Quiet", {}, { it }, Modifier.fillMaxWidth(), enabled = false)
    }
    Group("Segmented with options that do not fit on one line") {
        var mop by remember { mutableStateOf("Medium") }
        SegmentedControl(MOP, mop, { mop = it }, { it }, Modifier.fillMaxWidth())
    }
    Group("Passes: chosen, pressed (2), pending, disabled") {
        Wrap {
            var passes by remember { mutableIntStateOf(1) }
            PassSelector(passes, { passes = it })
            PassSelector(1, {}, Modifier.testTag(PRESS))
            PassSelector(2, {}, pending = true)
            PassSelector(3, {}, enabled = false)
        }
    }
    Group("Room chips: selected, unselected, long names, pressed (Hall), disabled") {
        val tones = roomTones()
        val picked = remember { mutableStateOf(setOf("Kitchen", "Unnamed room 2")) }
        ChipFlow {
            for ((i, name) in listOf("Kitchen", "Living room", "Unnamed room 2", "Downstairs guest bedroom").withIndex()) {
                val (fill, edge) = tones[i]
                SwatchChip(name, name in picked.value, { picked.value = picked.value.xor(name) }, fill, edge)
            }
            SwatchChip("Hall", false, {}, tones[1].first, tones[1].second, Modifier.testTag(PRESS))
            SwatchChip("Laundry", false, {}, tones[2].first, tones[2].second, enabled = false)
            SwatchChip("Study", true, {}, tones[3].first, tones[3].second, enabled = false)
        }
    }
}

private fun Set<String>.xor(name: String) = if (name in this) this - name else this + name

// Four of the floor plan's room slots (selected and edge tones), copied here only to show chips.
@Composable
private fun roomTones(): List<Pair<Color, Color>> {
    val dark = Rah.colors.page.luminance() < 0.5f
    val light = listOf(
        rgb(216, 144, 143) to rgb(126, 54, 56),
        rgb(134, 213, 186) to rgb(5, 94, 73),
        rgb(132, 206, 241) to rgb(7, 88, 116),
        rgb(193, 185, 248) to rgb(80, 68, 130),
    )
    val night = listOf(
        rgb(112, 57, 58) to rgb(232, 157, 156),
        rgb(43, 112, 91) to rgb(118, 199, 171),
        rgb(42, 106, 134) to rgb(117, 191, 227),
        rgb(97, 90, 139) to rgb(179, 171, 234),
    )
    return if (dark) night else light
}

private fun rgb(r: Int, g: Int, b: Int) = Color(r, g, b)

@Composable
private fun SwitchesPage() {
    Column {
        var remote by remember { mutableStateOf(true) }
        SwitchRow("Remote access", remote, { remote = it }, summary = "Reach the app from outside your home network")
        SwitchRow("Off, no summary", false, {})
        SwitchRow("Pending, turning on", true, {}, pending = true)
        SwitchRow("Pending, turning off", false, {}, pending = true)
        SwitchRow("Pressed", false, {}, Modifier.testTag(PRESS))
        SwitchRow("Disabled", true, {}, summary = "Needs the Roborock account", enabled = false)
    }
    Group("Several on at once, as the days of a schedule: weekdays, then weekends with Thu pressed") {
        var days by remember { mutableStateOf(setOf("Mon", "Tue", "Wed", "Thu", "Fri")) }
        MultiSegmentedControl(DAYS, days, { days = days.xor(it) }, { it })
        MultiSegmentedControl(DAYS, setOf("Sat", "Sun"), {}, { it }, Modifier.testTag(PRESS))
    }
    Group("A switch on its own: on, off, pending, pressed, disabled") {
        Row(horizontalArrangement = Arrangement.spacedBy(Rah.space.s12)) {
            var on by remember { mutableStateOf(true) }
            ToggleSwitch(on, { on = it }, "On")
            ToggleSwitch(false, {}, "Off")
            ToggleSwitch(true, {}, "Pending", pending = true)
            ToggleSwitch(false, {}, "Pressed", Modifier.testTag(PRESS))
            ToggleSwitch(true, {}, "Disabled", enabled = false)
        }
    }
}

@Composable
private fun SettingsIndexPage() {
    var look by remember { mutableStateOf("Match device") }
    SectionHeading("Appearance", Modifier.padding(horizontal = Rah.space.gutter))
    Column {
        RadioGroup(
            options = listOf("Match device", "Light", "Dark"),
            selected = look,
            onSelect = { look = it },
            label = { it },
            supporting = "This setting applies wherever you sign in.",
        )
        RadioRow("Pressed", selected = false, onClick = {}, modifier = Modifier.testTag(PRESS))
        RadioRow("Disabled", selected = true, onClick = {}, summary = "With a summary under it", enabled = false)
    }
    Hairline()
    Column {
        IndexRow("Time zone", "Australia/Melbourne", {})
        IndexRow("People", "2 people can sign in", {}, Modifier.testTag(PRESS))
        IndexRow("Remote access", "Needs the Roborock account", {}, enabled = false)
    }
}

@Composable
private fun FieldsPage() {
    Column(
        Modifier.padding(horizontal = Rah.space.gutter),
        verticalArrangement = Arrangement.spacedBy(Rah.space.s16),
    ) {
        var address by remember { mutableStateOf("") }
        LabelledField(
            address, { address = it }, "Server address",
            supporting = "For example, http://192.168.1.20:8080",
        )
        var name by remember { mutableStateOf("anna@example.com") }
        LabelledField(name, { name = it }, "Email or name, focused", Modifier.testTag(FOCUS))
        LabelledField("http://192.168.1.2", {}, "Server address", error = "Could not reach a server at this address")
        LabelledField("Downstairs", {}, "Disabled", enabled = false)
        var pw by remember { mutableStateOf("correct horse") }
        PasswordField(pw, { pw = it }, "Password, hidden")
        PasswordField(pw, { pw = it }, "Password, shown (the tests press Show)")
        PasswordField("hunter2", {}, "Password", error = "Wrong email or password")
    }
}

@Composable
private fun FeedbackPage() {
    Group("Attention banner, with and without an action") {}
    AttentionBanner(
        title = "Main brush jammed",
        detail = "Last seen in Kitchen at 10:42 AM with 40% battery.",
        action = BannerAction("Show on map", Glyph.Pin) {},
    )
    AttentionBanner(title = "Battery low", detail = "The robot is in Bedroom with 4% battery and is not charging.")
    Group("Inline error, then a standing warning") {
        ErrorText("Could not save the schedule. Check the connection and try again.")
        WarningText("The Roborock account needs signing in again")
    }
    Group("Connection strip") {}
    ConnectionStrip()
    Group("Messages: success, then failure") {}
    val success = remember { Messages().apply { success("Time zone set to Australia/Melbourne") } }
    val failure = remember { Messages().apply { failure("Downstairs did not answer") } }
    MessageHost(success)
    MessageHost(failure)
    Group("Success messages leave by themselves; this shows another") {
        QuietButton("Show the success message again", { success.success("Time zone set to Australia/Melbourne") })
    }
}

@Composable
private fun FramePage() {
    Group("Top bars: plain, with back and an action, with a long title") {}
    TopBar("Schedules")
    TopBar("Edit schedule", onBack = {}, actions = { PrimaryButton("Save", {}) })
    TopBar("Downstairs guest bedroom settings", onBack = {})
    Group("Outlined card with its own switch") {
        var on by remember { mutableStateOf(true) }
        BorderedCard(onClick = {}, onClickLabel = "edit") {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("9:30 am", style = Rah.type.pageTitle.tabular())
                    Text("Weekdays", style = Rah.type.label)
                }
                ToggleSwitch(on, { on = it }, "Schedule at 9:30 am on weekdays")
            }
            Spacer(Modifier.height(Rah.space.s8))
            Text("Downstairs", style = Rah.type.label)
            Text("Kitchen, Living room and Downstairs guest bedroom, twice", style = Rah.type.supporting, color = Rah.colors.textSupporting)
            Text("Next: tomorrow at 9:30 am", style = Rah.type.supporting, color = Rah.colors.textSupporting)
        }
        BorderedCard {
            Text("A card that only shows something", style = Rah.type.body)
        }
    }
}

@Composable
private fun BottomBarPage() {
    Group("Robot chosen, then Settings. Four across while the labels fit; at larger text, two rows of two.") {}
    var destination by remember { mutableStateOf(Destination.Robot) }
    BottomNavBar(destination, { destination = it })
    BottomNavBar(Destination.Settings, {})
}

/** Something behind a confirmation dialog, which opens straight away when [open] starts true. */
@Composable
fun DialogPage(startOpen: Boolean) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.padding(Rah.space.gutter), verticalArrangement = Arrangement.spacedBy(Rah.space.s16)) {
        Text("Dialog", style = Rah.type.pageTitle)
        SecondaryButton("Show the confirmation dialog", { open = true })
    }
    if (open) {
        ConfirmDialog(
            title = "Delete this schedule?",
            text = "Downstairs will no longer clean at 9:30 am on weekdays.",
            confirmLabel = "Delete schedule",
            onConfirm = { open = false },
            onDismiss = { open = false },
        )
    }
}
