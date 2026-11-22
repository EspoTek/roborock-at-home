package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

// The rows run edge to edge and carry the screen's gutter inside them, so the press shading spans
// the width. Give a SectionHeading or text beside them the same gutter.

/**
 * A setting that is on or off: [title], an optional [summary], and a switch at the end. The whole
 * row toggles. While [pending] the thumb turns and the row cannot be toggled.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    pending: Boolean = false,
) {
    val c = Rah.colors
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled && !pending, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { if (pending) stateDescription = pendingState(checked) }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s12),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
        SwitchVisual(checked, enabled, pending)
    }
}

/**
 * A switch on its own, for where a whole row cannot be the target, such as the corner of a schedule
 * card. TalkBack reads [contentDescription] ("Downstairs schedule at 9:30 AM on weekdays"). Pending
 * works as on [SwitchRow].
 */
@Composable
fun ToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .toggleable(value = checked, enabled = enabled && !pending, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics {
                this.contentDescription = contentDescription
                if (pending) stateDescription = pendingState(checked)
            },
        contentAlignment = Alignment.Center,
    ) {
        SwitchVisual(checked, enabled, pending)
    }
}

private fun pendingState(checked: Boolean) = (if (checked) "On, " else "Off, ") + IN_PROGRESS.lowercase()

@Composable
private fun SwitchVisual(checked: Boolean, enabled: Boolean, pending: Boolean) {
    val c = Rah.colors
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        thumbContent = if (pending) {
            { PendingSpinner(color = if (checked) c.accent else c.surfaceSecondary) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            checkedIconColor = c.accent,
            uncheckedThumbColor = c.textQuiet,
            uncheckedTrackColor = c.surfaceSecondary,
            uncheckedBorderColor = c.textQuiet,
            uncheckedIconColor = c.surfaceSecondary,
        ),
    )
}

/**
 * One choice of several as a full-width row with a radio button at the start. Use [RadioGroup] for a
 * set of them; a lone RadioRow is for when other content has to sit between the choices.
 */
@Composable
fun RadioRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val c = Rah.colors
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = Rah.size.target)
            .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s12),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.textQuiet),
        )
        RowText(text, summary, enabled, Modifier.weight(1f))
    }
}

/**
 * A set of [RadioRow]s, one per option, with an optional [supporting] line under them, such as
 * Appearance: Match device, Light, Dark, then "This setting applies wherever you sign in."
 * [selected] is null while nothing is chosen yet, such as a setting the server has not told yet.
 */
@Composable
fun <T> RadioGroup(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    require(selected == null || selected in options) { "The selected option $selected is not one of $options" }
    Column(modifier.selectableGroup()) {
        for (option in options) {
            RadioRow(label(option), option == selected, onClick = { onSelect(option) }, enabled = enabled)
        }
        if (supporting != null) {
            // In line with the options' text: past the radio button and its gap.
            Text(
                supporting,
                style = Rah.type.supporting,
                color = Rah.colors.textSupporting,
                modifier = Modifier.padding(
                    start = Rah.space.gutter + Rah.size.icon + Rah.space.s16,
                    end = Rah.space.gutter,
                    bottom = Rah.space.s8,
                ),
            )
        }
    }
}

/** An entry in a settings index that opens a subpage: [title], [summary] of what is set now, and a chevron. */
@Composable
fun IndexRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s12),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, enabled, Modifier.weight(1f))
        GlyphIcon(
            Glyph.ChevronRight,
            contentDescription = null,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            tint = Rah.colors.textQuiet,
        )
    }
}

/** Names a group of controls or rows ("Rooms", "Suction", "Appearance"). TalkBack treats it as a heading. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Rah.type.sectionHeading,
        color = Rah.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A group of controls under its [SectionHeading], every one 12 dp below the one above: Rooms,
 * Suction, Days. Where the controls carry the screen's gutter themselves, such as rows, give the
 * heading the same gutter with [headingModifier].
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    headingModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Rah.space.s12)) {
        SectionHeading(title, headingModifier)
        content()
    }
}

/** A 1 dp line in the outline colour between rows or regions. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Rah.colors.outline))
}

// Only the title fades when the row is disabled, with an index row's chevron: the summary is often
// what explains why, such as "Needs the Roborock account", so it stays readable.
@Composable
private fun RowText(title: String, summary: String?, enabled: Boolean, modifier: Modifier) {
    val c = Rah.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Rah.type.body, color = c.text, modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA))
        if (summary != null) Text(summary, style = Rah.type.supporting, color = c.textSupporting)
    }
}
