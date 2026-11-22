package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

/**
 * What a screen shows when it has nothing to list yet: a quiet icon, [title] and a sentence of
 * [text] saying what will appear or what to do, then anything [more] adds, such as a standing warning.
 * It goes in the screen's gutter and adds no margin of its own at the sides.
 */
@Composable
fun EmptyState(
    glyph: Glyph,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    more: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Rah.space.s12),
    ) {
        GlyphIcon(glyph, contentDescription = null, tint = Rah.colors.textQuiet, size = 44.dp)
        Text(
            title,
            style = Rah.type.sectionHeading,
            color = Rah.colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(text, style = Rah.type.supporting, color = Rah.colors.textSupporting, textAlign = TextAlign.Center)
        more()
    }
}
