package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahType

/**
 * The title bar of a destination or a subpage: a back button when [onBack] is given, the [title]
 * (which wraps rather than being cut off at large text), and [actions] at the end, such as an
 * editor's Save. Put it in [ScreenFrame]'s top bar slot, which keeps it clear of the status bar.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = if (onBack == null) Rah.space.gutter else Rah.space.s4, end = Rah.space.s12),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconOnlyButton(Glyph.Back, contentDescription = "Back", onClick = onBack)
        Text(
            title,
            style = Rah.type.pageTitle,
            color = Rah.colors.text,
            modifier = Modifier.weight(1f).padding(vertical = Rah.space.s8).semantics { heading() },
        )
        actions()
    }
}

/** The app's four destinations, in bottom bar order. */
enum class Destination(val label: String, val glyph: Glyph) {
    Robot("Robot", Glyph.Robot),
    Schedules("Schedules", Glyph.Calendar),
    Activity("Activity", Glyph.Activity),
    Settings("Settings", Glyph.Settings),
}

/**
 * The bottom bar with the four destinations: a flat surface under a fine divider, the selected one
 * in the accent with a quiet indicator. Four across, each icon above its label, while every label
 * fits its quarter at the person's text size; otherwise two rows of two in the same order, each icon
 * beside its label. Labels always take the full system font size and keep whole words. The system
 * navigation inset is added underneath, and on a wide window the items keep to the same 600 dp
 * column as the screens.
 */
@Composable
fun BottomNavBar(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Rah.colors.surface)) {
        Hairline()
        BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            if (labelsFitAcross(maxWidth)) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).selectableGroup()) {
                    for (d in Destination.entries) NavItem(d, d == selected, beside = false, onClick = { onSelect(d) })
                }
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    for (pair in Destination.entries.chunked(2)) {
                        Row(Modifier.fillMaxWidth()) {
                            for (d in pair) NavItem(d, d == selected, beside = true, onClick = { onSelect(d) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private val LABEL_INSET = 2.dp
private val NAV_SELECTED = RahType.navigation.copy(fontWeight = FontWeight.SemiBold)

// Whether every label, at the person's text size and in the heavier selected weight, fits on one
// line in a quarter of the bar.
@Composable
private fun labelsFitAcross(width: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(width, density) {
        val cell = with(density) { (width / Destination.entries.size - LABEL_INSET * 2).roundToPx() }
        Destination.entries.all { d ->
            measurer.measure(d.label, NAV_SELECTED, softWrap = false, maxLines = 1).size.width <= cell
        }
    }
}

@Composable
private fun RowScope.NavItem(destination: Destination, selected: Boolean, beside: Boolean, onClick: () -> Unit) {
    val c = Rah.colors
    val ink = if (selected) c.accent else c.textSupporting
    // The whole cell takes the tap; the press shows on the indicator, as the selection does.
    val interaction = remember { MutableInteractionSource() }
    val indicator = Modifier
        .clip(Rah.shapes.chip)
        .background(if (selected) c.accentSurface else c.surface)
        .indication(interaction, LocalIndication.current)
    val label = @Composable {
        Text(
            destination.label,
            style = Rah.type.navigation.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = LABEL_INSET),
        )
    }
    CompositionLocalProvider(LocalContentColor provides ink) {
        Box(
            Modifier
                .weight(1f)
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .defaultMinSize(minHeight = if (beside) 56.dp else 64.dp)
                .padding(vertical = Rah.space.s8, horizontal = if (beside) Rah.space.s8 else 0.dp),
            // Two rows keep their icons in line down each column, on the gutter.
            contentAlignment = if (beside) Alignment.CenterStart else Alignment.Center,
        ) {
            if (beside) {
                // One indicator holds the icon and the label.
                Row(
                    indicator.padding(horizontal = Rah.space.s8, vertical = Rah.space.s4),
                    horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(destination.glyph, contentDescription = null)
                    label()
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Rah.space.s4),
                ) {
                    Box(indicator.size(width = 56.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                        GlyphIcon(destination.glyph, contentDescription = null)
                    }
                    label()
                }
            }
        }
    }
}
