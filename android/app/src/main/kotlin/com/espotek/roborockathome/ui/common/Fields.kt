package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah

/**
 * An outlined text field with its [label] always shown above it. Under it goes [error] when there is
 * one, otherwise [supporting] text. An empty field is blank: an example of what to type goes in
 * [supporting], beginning "For example," ("For example, http://192.168.1.20:8080"), so it cannot be
 * mistaken for something already entered.
 */
@Composable
fun LabelledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    FieldFrame(
        value, onValueChange, label, modifier, supporting, error, enabled,
        keyboardOptions, keyboardActions, VisualTransformation.None, trailing = null,
    )
}

/** A [LabelledField] for a password, hidden as it is typed, with a button that shows or hides it. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    FieldFrame(
        value, onValueChange, label, modifier, supporting, error, enabled = true,
        KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction), keyboardActions,
        if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconOnlyButton(
                glyph = if (shown) Glyph.Hide else Glyph.Show,
                contentDescription = if (shown) "Hide password" else "Show password",
                onClick = { shown = !shown },
                tint = Rah.colors.textSupporting,
            )
        },
    )
}

@Composable
private fun FieldFrame(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    supporting: String?,
    error: String?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation,
    trailing: (@Composable () -> Unit)?,
) {
    val c = Rah.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline = if (error != null) c.attention else if (focused) c.accent else c.controlOutline
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        // The label, the box and the line under it are all one field: TalkBack reads them together
        // and a tap anywhere on them focuses it.
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics { if (error != null) this.error(error) },
        enabled = enabled,
        textStyle = Rah.type.body.copy(color = c.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(c.accent),
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
                Text(label, style = Rah.type.label, color = c.textSupporting)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Rah.size.target)
                        .background(c.surface, Rah.shapes.control)
                        .border(if (focused || error != null) 2.dp else 1.dp, outline, Rah.shapes.control)
                        .padding(start = Rah.space.s16, end = if (trailing == null) Rah.space.s16 else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = Rah.space.s12)) {
                        field()
                    }
                    if (trailing != null) trailing()
                }
                if (error != null) {
                    // TalkBack already hears the error through the field's own error state.
                    AttentionLine(error, Modifier.clearAndSetSemantics {})
                } else if (supporting != null) {
                    Text(supporting, style = Rah.type.supporting, color = c.textSupporting)
                }
            }
        },
    )
}
