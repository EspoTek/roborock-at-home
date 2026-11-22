package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

/**
 * The main action of a screen or of a row of actions, filled with the accent. While [busy] the
 * button keeps its colours and its width, a bar runs along its foot, and it cannot be pressed.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) = KitButton(text, onClick, Tone.Primary, modifier, glyph, enabled, busy)

/** A secondary action beside or below the main one, outlined. Busy works as on [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) = KitButton(text, onClick, Tone.Secondary, modifier, glyph, enabled, busy)

/** A low-key action in accent text without an outline, such as "Use a different email". */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) = KitButton(text, onClick, Tone.Quiet, modifier, glyph, enabled, busy)

/** An action that removes or ends something (Delete, Unlink account, Sign out), outlined with attention-coloured text. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) = KitButton(text, onClick, Tone.Danger, modifier, glyph, enabled, busy)

/**
 * An icon-only control with a 48 dp target, such as zoom, recentre, clear or close. TalkBack reads
 * [contentDescription], which says what pressing it does ("Zoom in", "Remove Anna").
 */
@Composable
fun IconOnlyButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Rah.colors.text,
) {
    CompositionLocalProvider(LocalContentColor provides tint) {
        Box(
            modifier
                .size(Rah.size.target)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(Rah.shapes.control)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, contentDescription)
        }
    }
}

/**
 * A [SecondaryButton] as its icon alone, for a row of actions whose labels do not all fit: outlined,
 * with a 48 dp target and the same bar while [busy]. TalkBack reads [contentDescription], the
 * label it stands for ("Go to dock").
 */
@Composable
fun SecondaryIconButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val c = Rah.colors
    val shape = Rah.shapes.control
    CompositionLocalProvider(LocalContentColor provides c.text) {
        Box(
            modifier
                .size(Rah.size.target)
                .clip(shape)
                .border(1.dp, c.controlOutline, shape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    if (busy) stateDescription = IN_PROGRESS
                },
            contentAlignment = Alignment.Center,
        ) {
            // A standalone action's glyph, 24 dp like the other icon-only buttons'.
            GlyphIcon(glyph, contentDescription = null)
            if (busy) PendingBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).width(24.dp))
        }
    }
}

internal enum class Tone { Primary, Secondary, Quiet, Danger, DangerFilled }

internal const val DISABLED_ALPHA = 0.45f
internal const val IN_PROGRESS = "In progress"

@Composable
internal fun KitButton(
    text: String,
    onClick: () -> Unit,
    tone: Tone,
    modifier: Modifier,
    glyph: Glyph?,
    enabled: Boolean,
    busy: Boolean,
) {
    val c = Rah.colors
    val container = when (tone) {
        Tone.Primary -> c.accent
        Tone.DangerFilled -> c.attention
        Tone.Secondary, Tone.Quiet, Tone.Danger -> Color.Transparent
    }
    val content = when (tone) {
        Tone.Primary -> c.onAccent
        Tone.Secondary -> c.text
        Tone.Quiet -> c.accent
        Tone.Danger -> c.attention
        Tone.DangerFilled -> c.onAttention
    }
    val outline = when (tone) {
        Tone.Secondary, Tone.Danger -> c.controlOutline
        Tone.Primary, Tone.Quiet, Tone.DangerFilled -> Color.Transparent
    }
    CompositionLocalProvider(LocalContentColor provides content) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                // Busy keeps its full colours: the bar and the ignored taps say it is working.
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(Rah.shapes.control)
                .background(container)
                .border(1.dp, outline, Rah.shapes.control)
                .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
                .semantics { if (busy) stateDescription = IN_PROGRESS }
                .defaultMinSize(minHeight = Rah.size.target)
                .padding(horizontal = Rah.space.s16, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (glyph != null) GlyphIcon(glyph, contentDescription = null, size = 20.dp)
                Text(text, style = Rah.type.button, textAlign = TextAlign.Center)
            }
            // In a layer the size of the label, so the bar can never widen the button.
            if (busy) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                    PendingBar(Modifier.fillMaxWidth().offset(y = 4.dp))
                }
            }
        }
    }
}
