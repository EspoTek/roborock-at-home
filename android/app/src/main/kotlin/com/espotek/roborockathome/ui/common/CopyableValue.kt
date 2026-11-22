package com.espotek.roborockathome.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah

/**
 * A value to copy, such as the remote address, a new token or what the app could not read: [label]
 * over it, the value as selectable text across a read-only box, and Copy under it. The box takes
 * the whole width, so a long address or token breaks over as few lines as it can.
 */
@Composable
fun CopyableValue(label: String, value: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val c = Rah.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            Text(label, style = Rah.type.label, color = c.textSupporting)
            SelectionContainer {
                Text(
                    value,
                    style = Rah.type.body.copy(fontFamily = FontFamily.Monospace),
                    color = c.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Rah.size.target)
                        .background(c.surfaceSecondary, Rah.shapes.control)
                        .border(1.dp, c.outline, Rah.shapes.control)
                        .padding(horizontal = Rah.space.s16, vertical = Rah.space.s12),
                )
            }
        }
        SecondaryButton("Copy", onCopy, glyph = Glyph.Copy)
    }
}

/** Puts [text] on the clipboard. A [secret] is marked sensitive, so Android leaves it out of its clipboard preview. */
fun Context.copyText(label: String, text: String, secret: Boolean) {
    val clip = ClipData.newPlainText(label, text)
    // Android before 13 ignores the mark.
    if (secret) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    checkNotNull(getSystemService(ClipboardManager::class.java)) { "Android always has a clipboard" }.setPrimaryClip(clip)
}
