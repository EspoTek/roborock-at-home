package com.espotek.roborockathome.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import com.espotek.roborockathome.ui.common.Messages
import com.espotek.roborockathome.ui.common.rememberMessages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shell around the demo's screens: build/shots/shell-*.png. */
@RunWith(RobolectricTestRunner::class)
class ShellShots {
    @Test fun robot() = shot("shell") { DemoShell() }

    @Test fun settingsChosen() = shot("shell-settings", act = { onNodeWithText("Settings").performClick() }) { DemoShell() }

    @Test fun connectingWithFailure() = shot("shell-connecting-failure") { ReconnectingWithFailure() }

    @Test fun success() = shot("shell-success") { WithSuccess() }

    @Test fun smallPhone() = shot("shell-connecting-failure", ShotSize.SmallPhone) { ReconnectingWithFailure() }

    @Test fun largeText() = shot("shell-connecting-failure", fontScale = 2f) { ReconnectingWithFailure() }

    // The narrowest phone at 2x text, where the bottom bar goes to two rows, under the strip and a message.
    @Test fun smallPhoneLargeText() = shot("shell-connecting-failure", ShotSize.SmallPhone, fontScale = 2f) { ReconnectingWithFailure() }

    @Test fun landscape() = shot("shell-success", ShotSize.Landscape) { WithSuccess() }

    // The schedule editor takes the whole screen: the bottom bar is hidden while it is open.
    @Test fun editor() = shot("shell-editor", act = { newSchedule() }) { DemoShell() }

    @Test fun editorSmallPhone() = shot("shell-editor", ShotSize.SmallPhone, act = { newSchedule() }) { DemoShell() }

    private fun ComposeUiTest.newSchedule() {
        onNodeWithText("Schedules").performClick()
        onNodeWithText("New schedule").performClick()
    }

    @Composable
    private fun DemoShell(home: Home = Fixtures.demo, messages: Messages = rememberMessages()) =
        Shell(home, remember { Fixtures.store() }, Fixtures.SERVER_URL, onSignOut = {}, messages = messages)

    @Composable
    private fun ReconnectingWithFailure() = DemoShell(
        Fixtures.demo.copy(link = Link.Reconnecting),
        remember { Messages().apply { failure("Downstairs did not answer") } },
    )

    @Composable
    private fun WithSuccess() = DemoShell(messages = remember { Messages().apply { success("Time zone set to Australia/Melbourne") } })
}
