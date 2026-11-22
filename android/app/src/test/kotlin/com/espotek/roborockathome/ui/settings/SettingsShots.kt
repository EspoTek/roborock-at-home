package com.espotek.roborockathome.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.espotek.roborockathome.core.model.Tunnel
import com.espotek.roborockathome.core.model.TunnelMode
import com.espotek.roborockathome.core.model.TunnelStatus
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.settings.SettingsFixtures.withApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings as pictures: build/shots/settings-*.png. */
@RunWith(RobolectricTestRunner::class)
class SettingsShots {
    @Test fun index() = shot("settings-index") { Index() }

    @Test fun indexLargeText() = shot("settings-index", ShotSize.Tall, fontScale = 2f) { Index() }

    @Test fun indexSmallPhone() = shot("settings-index", ShotSize.SmallPhone) { Index() }

    @Test fun indexSmallPhoneLargeText() = shot("settings-index", ShotSize.SmallPhone, fontScale = 2f) { Index() }

    @Test fun indexConnecting() = shot("settings-index-connecting") { Index(Fixtures.connecting, users = false) }

    @Test fun accountLinked() = shot("settings-account-linked") { Account(SettingsFixtures.linked) }

    @Test fun accountProblem() = shot("settings-account-problem") { Account(SettingsFixtures.loginExpired) }

    @Test fun accountProblemSmallPhone() = shot("settings-account-problem", ShotSize.SmallPhone) { Account(SettingsFixtures.loginExpired) }

    @Test fun accountRelink() = shot("settings-account-relink", act = { onNodeWithText("Link account again").performClick() }) {
        Account(SettingsFixtures.loginExpired)
    }

    @Test fun accountCode() = shot("settings-account-code") {
        Account(Fixtures.notLinked, AccountDraft("you@example.com", codeSentTo = "you@example.com"), remember { Secrets().apply { code = "4829" } })
    }

    @Test fun unlinkDialog() = shot("settings-account-unlink", act = { onNodeWithText("Unlink account").performClick() }) {
        Account(SettingsFixtures.linked)
    }

    @Test fun timeZone() = shot("settings-time-zone") {
        TimeZonePage(app(Fixtures.demo), store().api, remember { platformZones() }, onBack = {}, draft = remember { ZoneDraft("australia", "Australia/Sydney") })
    }

    @Test fun remoteRunning() = shot("settings-remote-running") { Remote(SettingsFixtures.linked) }

    @Test fun remoteRunningLargeText() = shot("settings-remote-running", ShotSize.Tall, fontScale = 2f) { Remote(SettingsFixtures.linked) }

    @Test fun remoteRunningSmallPhone() = shot("settings-remote-running", ShotSize.SmallPhone) { Remote(SettingsFixtures.linked) }

    @Test fun remoteStarting() = shot("settings-remote-starting") {
        Remote(Fixtures.demo.withApp { copy(tunnel = Tunnel(TunnelMode.Quick, TunnelStatus.Starting, null, null)) })
    }

    @Test fun remoteOff() = shot("settings-remote-off") { Remote(Fixtures.demo) }

    @Test fun people() = shot("settings-people") {
        PeoplePage(SettingsFixtures.users, store().api, remember { Secrets() }, rememberTimeWords(), onChanged = {}, onBack = {})
    }

    @Test fun tokensCreated() = shot("settings-tokens-created") { Tokens() }

    @Test fun tokensCreatedLargeText() = shot("settings-tokens-created", ShotSize.Tall, fontScale = 2f) { Tokens() }

    @Test fun tokensCreatedSmallPhone() = shot("settings-tokens-created", ShotSize.SmallPhone) { Tokens() }

    @Test fun passwordRefused() = shot("settings-password-error", act = { onNodeWithText("Change password").performClick() }) {
        PasswordPage(
            remember { Fixtures.refusingStore("The current password is wrong.", code = "bad_request", status = 400) }.api,
            remember { Secrets().apply { currentPassword = "hunter2hunter"; newPassword = "correct horse battery" } },
            onBack = {},
        )
    }

    @Composable
    private fun Index(home: Home = SettingsFixtures.linked, users: Boolean = true) = SettingsIndex(
        home,
        store(),
        Fixtures.SERVER_URL,
        users = SettingsFixtures.users.takeIf { users },
        tokens = listOf(SettingsFixtures.homeAssistant, SettingsFixtures.agent).takeIf { users },
        stats = SettingsFixtures.stats.takeIf { users },
        words = rememberTimeWords(),
        onOpen = {},
        onSignOut = {},
    )

    @Composable
    private fun Account(home: Home, draft: AccountDraft = remember { AccountDraft() }, secrets: Secrets = remember { Secrets() }) =
        AccountPage(app(home), store().api, secrets, onBack = {}, draft = draft)

    @Composable
    private fun Remote(home: Home) = RemoteAccessPage(app(home).tunnel, store().api, remember { Secrets() }, onBack = {})

    @Composable
    private fun Tokens() = TokensPage(
        listOf(SettingsFixtures.homeAssistant, SettingsFixtures.agent),
        store().api,
        remember { Secrets().apply { newToken = SettingsFixtures.newAgent } },
        rememberTimeWords(),
        Fixtures.SERVER_URL,
        onChanged = {},
        onBack = {},
    )

    @Composable
    private fun store(): HomeStore = remember { Fixtures.store() }

    private fun app(home: Home) = checkNotNull(home.app)
}
