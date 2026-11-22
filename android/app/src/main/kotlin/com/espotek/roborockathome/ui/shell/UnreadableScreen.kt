package com.espotek.roborockathome.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.common.CopyableValue
import com.espotek.roborockathome.ui.common.DangerButton
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.copyText
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah

/**
 * In place of the app while the server has sent something this version cannot read. Try again
 * starts over; Sign out leaves for the sign-in form. [problem] (`Home.problem`) waits under
 * Technical details, closed at first, to read or copy.
 */
@Composable
fun UnreadableScreen(
    problem: String,
    onTryAgain: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Rah.colors
    val context = LocalContext.current
    var details by rememberSaveable { mutableStateOf(false) }
    StandalonePage(modifier) {
        GlyphIcon(Glyph.Alert, contentDescription = null, tint = c.attention, size = 32.dp)
        Spacer(Modifier.height(Rah.space.s16))
        Text(
            "The app couldn't read the server's response.",
            style = Rah.type.pageTitle,
            color = c.text,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Rah.space.s12))
        Text("Check that the app and server are up to date, then try again.", style = Rah.type.body, color = c.text)
        Spacer(Modifier.height(Rah.space.s32))
        PrimaryButton("Try again", onTryAgain, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Rah.space.s12))
        DangerButton("Sign out", onSignOut, Modifier.fillMaxWidth(), glyph = Glyph.SignOut)
        Spacer(Modifier.height(Rah.space.s24))
        DetailsToggle(details, onToggle = { details = !details })
        if (details) {
            Spacer(Modifier.height(Rah.space.s8))
            CopyableValue("Error", problem, onCopy = { context.copyText("Technical details", problem, secret = false) })
        }
    }
}

// "Technical details" with a chevron that points down while they are open.
@Composable
private fun DetailsToggle(open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Rah.shapes.control)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "Shown" else "Hidden" }
            .defaultMinSize(minHeight = Rah.size.target),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Technical details", style = Rah.type.label, color = Rah.colors.text)
        GlyphIcon(
            Glyph.ChevronRight,
            contentDescription = null,
            modifier = Modifier.rotate(if (open) 90f else 0f),
            tint = Rah.colors.textSupporting,
            size = 20.dp,
        )
    }
}
