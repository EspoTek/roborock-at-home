package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Rah

/**
 * A lightly outlined card for one item in a list, such as a schedule, with 16 dp inside. A tap
 * anywhere on it runs [onClick]; a switch or button inside it still does only its own thing.
 * [onClickLabel] finishes TalkBack's "double tap to ...", for example "edit".
 */
@Composable
fun BorderedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .cardFrame()
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(Rah.space.s16),
        content = content,
    )
}

/** The same card when it only shows something, such as a new token to copy. */
@Composable
fun BorderedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.cardFrame().padding(Rah.space.s16), content = content)
}

@Composable
private fun Modifier.cardFrame(): Modifier {
    val c = Rah.colors
    return fillMaxWidth()
        .clip(Rah.shapes.control)
        .background(c.surface)
        .border(1.dp, c.outline, Rah.shapes.control)
}
