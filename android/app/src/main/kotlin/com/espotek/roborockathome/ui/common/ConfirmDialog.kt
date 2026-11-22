package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.espotek.roborockathome.ui.theme.Rah

/**
 * Asks before something that cannot be undone: delete a schedule, discard unsaved changes, remove
 * a person. [confirmLabel] names the act ("Delete schedule", "Discard"), on a filled attention
 * button. Cancel, Back and a tap outside all call [onDismiss], which should leave everything as it was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogFrame(onDismiss) {
        Column(
            Modifier.padding(Rah.space.s24),
            verticalArrangement = Arrangement.spacedBy(Rah.space.s16),
        ) {
            Text(title, style = Rah.type.status, color = Rah.colors.text, modifier = Modifier.semantics { heading() })
            Text(text, style = Rah.type.body, color = Rah.colors.textSupporting)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Rah.space.s8),
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s8, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Rah.space.s8),
            ) {
                SecondaryButton("Cancel", onDismiss)
                KitButton(confirmLabel, onConfirm, Tone.DangerFilled, Modifier, glyph = null, enabled = true, busy = false)
            }
        }
    }
}

/**
 * The frame of every dialog: the page's backdrop, lighter than Android's dim, then the dialog's
 * surface and corners with no shadow, [edge] clear of the window's sides and at most 560 dp wide.
 * Back and a tap outside call [onDismiss]. [ConfirmDialog] is the usual one; a dialog of its own,
 * such as a picker, puts its title and buttons in [content] in the same way.
 */
@Composable
fun DialogFrame(onDismiss: () -> Unit, edge: Dp = Rah.space.s24, content: @Composable () -> Unit) {
    // The width is the kit's, not the platform theme's.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { window.setDimAmount(0.45f) }
        // No shadow: the backdrop sets it apart.
        Surface(
            modifier = Modifier.padding(horizontal = edge).widthIn(max = 560.dp),
            shape = Rah.shapes.dialog,
            color = Rah.colors.surface,
            content = content,
        )
    }
}
