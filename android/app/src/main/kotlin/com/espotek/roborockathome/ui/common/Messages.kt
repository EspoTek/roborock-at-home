package com.espotek.roborockathome.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahMotion
import kotlinx.coroutines.delay

/** One short message. A new Message is a new showing, even with the same text. */
class Message(val text: String, val failure: Boolean)

/**
 * The app's short messages, the page's toasts. From anywhere in the shell:
 * `LocalMessages.current.success("Time zone set to Australia/Melbourne")` or
 * `LocalMessages.current.failure("Downstairs did not answer")`. Each replaces the one showing.
 * Errors that belong to a field or a form go next to it instead, with [ErrorText].
 */
@Stable
class Messages {
    var current: Message? by mutableStateOf(null)
        private set

    /** Leaves by itself after a few seconds, longer when the person has asked Android for more time. */
    fun success(text: String) {
        current = Message(text, failure = false)
    }

    /** Stays until the person dismisses it or the next message replaces it. */
    fun failure(text: String) {
        current = Message(text, failure = true)
    }

    fun dismiss() {
        current = null
    }

    internal fun expire(message: Message) {
        if (current === message) current = null
    }

    internal companion object {
        // A failure has to survive rotation, since it waits for the person.
        val Saver = Saver<Messages, List<Any>>(
            save = { m -> m.current?.let { listOf(it.text, it.failure) } ?: emptyList() },
            restore = { saved -> Messages().apply { if (saved.isNotEmpty()) current = Message(saved[0] as String, saved[1] as Boolean) } },
        )
    }
}

/** The shell provides it; reading it anywhere else is a mistake and fails. */
val LocalMessages = staticCompositionLocalOf<Messages> { error("No Messages here: the shell provides them") }

@Composable
fun rememberMessages(): Messages = rememberSaveable(saver = Messages.Saver) { Messages() }

/** Where [messages] appear: in their own space above the bottom bar, so they never cover content. */
@Composable
fun MessageHost(messages: Messages, modifier: Modifier = Modifier) {
    val current = messages.current
    if (current != null && !current.failure) {
        val accessibility = checkNotNull(LocalAccessibilityManager.current) { "Android always has an accessibility manager" }
        LaunchedEffect(current) {
            delay(accessibility.calculateRecommendedTimeoutMillis(4000, containsIcons = true, containsText = true))
            messages.expire(current)
        }
    }
    AnimatedContent(
        targetState = current,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(tween(RahMotion.TRANSITION)) togetherWith fadeOut(tween(RahMotion.TRANSITION)))
                .using(SizeTransform(clip = true) { _, _ -> tween(RahMotion.TRANSITION) })
        },
        label = "message",
    ) { message ->
        if (message == null) {
            Box(Modifier.fillMaxWidth())
        } else {
            MessageCard(message, onDismiss = messages::dismiss)
        }
    }
}

@Composable
private fun MessageCard(message: Message, onDismiss: () -> Unit) {
    val c = Rah.colors
    val shape = Rah.shapes.control
    Box(Modifier.fillMaxWidth().padding(horizontal = Rah.space.gutter, vertical = Rah.space.s8)) {
        if (message.failure) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.attentionSurface, shape)
                    .border(1.dp, c.attention, shape)
                    .padding(start = Rah.space.s16)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Alert, contentDescription = null, tint = c.attention, size = 20.dp)
                Text(
                    message.text,
                    style = Rah.type.label,
                    color = c.text,
                    modifier = Modifier.weight(1f).padding(vertical = Rah.space.s12),
                )
                IconOnlyButton(Glyph.Close, contentDescription = "Dismiss", onClick = onDismiss, tint = c.text)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.text, shape)
                    .defaultMinSize(minHeight = Rah.size.target)
                    .padding(horizontal = Rah.space.s16, vertical = Rah.space.s12)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Check, contentDescription = null, tint = c.page, size = 20.dp)
                Text(message.text, style = Rah.type.label, color = c.page)
            }
        }
    }
}
