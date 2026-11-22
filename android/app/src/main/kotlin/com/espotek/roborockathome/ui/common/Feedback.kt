package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

/** The button an [AttentionBanner] offers, such as "Show on map" with [Glyph.Pin]. */
class BannerAction(val label: String, val glyph: Glyph?, val onClick: () -> Unit)

/**
 * The amber banner for a robot in trouble, at the top of its page: [title] is the actual problem
 * ("Main brush jammed"), [detail] where and when ("Last seen in Kitchen at 10:42 AM with 40%
 * battery."), and [action] what the person can do about it. TalkBack announces it when it appears or
 * changes.
 */
@Composable
fun AttentionBanner(title: String, detail: String, modifier: Modifier = Modifier, action: BannerAction? = null) {
    val c = Rah.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.attentionSurface)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(c.attention, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s12)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
    ) {
        LineIcon(Glyph.Alert, c.attention, Rah.type.sectionHeading, size = 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(title, style = Rah.type.sectionHeading, color = c.text)
                Text(detail, style = Rah.type.supporting, color = c.textSupporting)
            }
            if (action != null) SecondaryButton(action.label, action.onClick, glyph = action.glyph)
        }
    }
}

/** An error next to the field or form it belongs to ("Wrong password"). TalkBack announces it as it appears. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    AttentionLine(text, modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * A standing problem shown where it applies, such as "The Roborock account needs signing in again"
 * under the account. It looks like [ErrorText] but is not announced: it was not caused just now.
 */
@Composable
fun WarningText(text: String, modifier: Modifier = Modifier) {
    AttentionLine(text, modifier)
}

@Composable
internal fun AttentionLine(text: String, modifier: Modifier) {
    val c = Rah.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
        LineIcon(Glyph.Alert, c.attention, Rah.type.supporting, size = 20.dp)
        Text(text, style = Rah.type.supporting, color = c.attention)
    }
}

/** An icon centred on the first line of text set in [style], so it stays beside that line at any text size. */
@Composable
private fun LineIcon(glyph: Glyph, tint: Color, style: TextStyle, size: Dp) {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.height(line), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, contentDescription = null, tint = tint, size = size)
    }
}

/** A wait for something on its way, such as the history or a floor plan: a turning arc and [text], centred. */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PendingSpinner(color = Rah.colors.textSupporting)
        Text(text, style = Rah.type.supporting, color = Rah.colors.textSupporting)
    }
}

/**
 * The slim strip under the top bar while the app cannot reach the server. It takes its own height
 * rather than covering anything, and it is about the server, not a robot: a robot with no
 * connection shows that on its own status.
 */
@Composable
fun ConnectionStrip(modifier: Modifier = Modifier) {
    val c = Rah.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceSecondary)
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = Rah.space.gutter, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PendingSpinner(color = c.textSupporting)
        Text("Connecting to server", style = Rah.type.label, color = c.text)
    }
}
