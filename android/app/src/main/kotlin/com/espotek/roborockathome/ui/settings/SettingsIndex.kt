package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.Stats
import com.espotek.roborockathome.core.model.Token
import com.espotek.roborockathome.core.model.User
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.DangerButton
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.IndexRow
import com.espotek.roborockathome.ui.common.RadioGroup
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.Section
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.tabular

/**
 * The Settings index: Appearance, then a row for each page with what is set now, then About, the
 * server and Sign out. [users], [tokens] and [stats] are null until they have been fetched.
 */
@Composable
internal fun SettingsIndex(
    home: Home,
    store: HomeStore,
    serverUrl: String,
    users: List<User>?,
    tokens: List<Token>?,
    stats: Stats?,
    words: TimeWords,
    onOpen: (SettingsPage) -> Unit,
    onSignOut: () -> Unit,
) {
    val app = home.app
    ScreenFrame(topBar = { TopBar("Settings") }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Rah.space.s32)) {
            Appearance(home.session?.look, store)
            Hairline()
            Column(Modifier.padding(vertical = Rah.space.s8)) {
                for (page in SettingsPage.entries) {
                    // The server's own settings are not known until its first state.
                    val summary = when (page) {
                        SettingsPage.Account -> app?.let(::accountSummary)
                        SettingsPage.TimeZone -> app?.let(::timeZoneSummary)
                        SettingsPage.RemoteAccess -> app?.let { remoteSummary(it.tunnel) }
                        SettingsPage.People -> peopleSummary(users)
                        SettingsPage.Tokens -> tokensSummary(tokens)
                        SettingsPage.Password -> PASSWORD_SUMMARY
                    }
                    IndexRow(page.title, summary ?: "Waiting for the server", onClick = { onOpen(page) }, enabled = summary != null)
                }
            }
            Hairline()
            About(app?.version, stats, words)
            SignedIn(serverUrl, home.session?.name, onSignOut)
        }
    }
}

private val LOOKS = mapOf(Look.System to "Match device", Look.Light to "Light", Look.Dark to "Dark")

/** Null [look] is not known yet: the server has not said who is signed in. */
@Composable
private fun Appearance(look: Look?, store: HomeStore) {
    // One runner each: the look shows at once, so a second choice need not wait for the first.
    val runners = LOOKS.keys.associateWith { rememberRunner() }
    Section("Appearance", Modifier.padding(top = Rah.space.s16), headingModifier = Modifier.gutter()) {
        RadioGroup(
            options = LOOKS.keys.toList(),
            selected = look,
            onSelect = { option -> if (option != look) runners.getValue(option).launch { store.setLook(option) } },
            label = LOOKS::getValue,
            supporting = "This setting applies wherever you sign in.",
            enabled = look != null,
        )
    }
}

@Composable
private fun About(serverVersion: String?, stats: Stats?, words: TimeWords) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        // The form that takes PackageInfoFlags is Android 13 and later only.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        checkNotNull(info.versionName) { "The app has a version name" }
    }
    Section("About", Modifier.fillMaxWidth().gutter().padding(top = Rah.space.s24)) {
        Column(verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            for ((label, value) in aboutRows(appVersion, serverVersion, stats, words, LocalConfiguration.current.locales[0])) {
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(Rah.space.s16),
                ) {
                    Text(label, style = Rah.type.body, color = Rah.colors.textSupporting, modifier = Modifier.weight(1f))
                    Text(value, style = Rah.type.body.tabular(), color = Rah.colors.text)
                }
            }
        }
    }
}

/** The address signed in to, who as, and Sign out. */
@Composable
private fun SignedIn(serverUrl: String, name: String?, onSignOut: () -> Unit) {
    val c = Rah.colors
    Section("Server", Modifier.fillMaxWidth().gutter().padding(top = Rah.space.s24)) {
        Column(verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            SelectionContainer { Text(serverUrl, style = Rah.type.body, color = c.text) }
            if (name != null) {
                Text(
                    buildAnnotatedString {
                        append("Signed in as ")
                        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) { append(name) }
                    },
                    style = Rah.type.supporting,
                    color = c.textSupporting,
                )
            }
        }
        DangerButton("Sign out", onSignOut, Modifier.padding(top = Rah.space.s4), glyph = Glyph.SignOut)
    }
}
