package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.Tunnel
import com.espotek.roborockathome.core.model.TunnelMode
import com.espotek.roborockathome.core.model.TunnelSetting
import com.espotek.roborockathome.core.model.TunnelStatus
import com.espotek.roborockathome.ui.common.CopyableValue
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.common.SwitchRow
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.common.copyText
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Rah

/**
 * Remote access: the quick link switch, where the tunnel has got to, its address to copy, its
 * error, and either the permanent address by tunnel token or the way to turn one off.
 */
@Composable
internal fun RemoteAccessPage(tunnel: Tunnel, api: RoborockApi, secrets: Secrets, onBack: () -> Unit) {
    val context = LocalContext.current
    val messages = LocalMessages.current
    SubPage(SettingsPage.RemoteAccess.title, onBack) {
        Note("Remote access opens Roborock at Home from anywhere through Cloudflare, and everyone still needs to sign in.")
        val quick = rememberRunner()
        SwitchRow(
            "Quick link",
            checked = tunnel.mode == TunnelMode.Quick,
            onCheckedChange = { on -> quick.launch { api.setTunnel(if (on) TunnelSetting.Quick else TunnelSetting.Off) } },
            summary = "A free address that works straight away.",
            enabled = tunnel.mode != TunnelMode.Token,
            pending = quick.busy,
        )
        if (tunnel.mode != TunnelMode.Off) Status(tunnel.status)
        tunnel.url?.let { url ->
            CopyableValue("Address", url, modifier = Modifier.gutter(), onCopy = {
                context.copyText("Address", url, secret = false)
                messages.success("Address copied")
            })
            if (tunnel.mode == TunnelMode.Quick) Note("This link changes when the server restarts.")
        }
        tunnel.error?.let { WarningText(it, Modifier.gutter()) }
        when (tunnel.mode) {
            TunnelMode.Token -> {
                Statement("Using your own Cloudflare tunnel, so the address stays the same.")
                val off = rememberRunner()
                SecondaryButton(
                    "Turn off remote access",
                    onClick = { off.launch { api.setTunnel(TunnelSetting.Off) } },
                    modifier = Modifier.gutter(),
                    busy = off.busy,
                )
            }
            TunnelMode.Off -> PermanentAddress(api, secrets)
            // The switch above is all a quick link needs.
            TunnelMode.Quick -> {}
        }
    }
}

/** Where the tunnel has got to, beside a dot: the accent when on, attention when it is not working. */
@Composable
private fun Status(status: TunnelStatus) {
    val c = Rah.colors
    val dot = when (status) {
        TunnelStatus.Running -> c.accent
        TunnelStatus.Error -> c.attention
        TunnelStatus.Stopped, TunnelStatus.Installing, TunnelStatus.Starting -> c.textQuiet
    }
    Row(
        Modifier.gutter().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Text(tunnelStatus(status), style = Rah.type.sectionHeading, color = c.text)
    }
}

@Composable
private fun PermanentAddress(api: RoborockApi, secrets: Secrets) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val connect = {
        if (errors.check(tunnelTokenProblems(secrets.tunnelToken))) {
            runner.launch(onError = { errors.refusal = it }) {
                api.setTunnel(TunnelSetting.OwnTunnel(secrets.tunnelToken.trim()))
                secrets.tunnelToken = ""
            }
        }
    }
    PageSection("Use a permanent address") {
        Note("Make a tunnel in the Cloudflare dashboard and paste its token here.")
        LabelledField(
            secrets.tunnelToken,
            onValueChange = {
                secrets.tunnelToken = it
                errors.edited(Field.TunnelToken)
            },
            label = "Tunnel token",
            modifier = Modifier.gutter(),
            error = errors.problems[Field.TunnelToken],
            // A password keyboard, so the token is not learnt or suggested back.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { connect() }),
        )
        errors.refusal?.let { ErrorText(it, Modifier.gutter()) }
        PrimaryButton("Connect", onClick = connect, modifier = Modifier.gutter(), busy = runner.busy)
    }
}
