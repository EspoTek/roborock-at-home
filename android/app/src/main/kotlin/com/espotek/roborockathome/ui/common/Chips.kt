package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

/**
 * Something the person picks, several at a time, such as a room. Unselected it shows a small swatch
 * of [fill]; selected it is filled with [fill] and shows a check. [edge] outlines the swatch and the
 * selected chip. The caller passes the colours (for rooms, the floor plan's tones). The label is
 * never shortened: a long one wraps inside the chip. Lay chips out with [ChipFlow].
 */
@Composable
fun SwatchChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fill: Color,
    edge: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Rah.colors
    Row(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(Rah.shapes.chip)
            .background(if (selected) fill else c.surface)
            .border(1.dp, if (selected) edge else c.controlOutline, Rah.shapes.chip)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .defaultMinSize(minHeight = Rah.size.target)
            .padding(horizontal = Rah.space.s12, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                GlyphIcon(Glyph.Check, contentDescription = null, tint = c.text, size = 18.dp)
            } else {
                val swatch = RoundedCornerShape(3.dp)
                Box(Modifier.size(12.dp).clip(swatch).background(fill).border(1.dp, edge, swatch))
            }
        }
        Text(label, style = Rah.type.label, color = c.text)
    }
}

/** Lays out chips in rows that wrap, 8 dp apart each way. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalArrangement = Arrangement.spacedBy(Rah.space.s8),
    ) { content() }
}
