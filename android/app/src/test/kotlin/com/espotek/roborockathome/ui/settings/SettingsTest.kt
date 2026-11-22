package com.espotek.roborockathome.ui.settings

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.TestFrame
import com.espotek.roborockathome.ui.common.Messages
import com.espotek.roborockathome.ui.common.rememberTimeWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The designer's phone, so the whole index is on the screen to be pressed.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SettingsTest {
    @get:Rule
    val app = createAndroidComposeRule<ComponentActivity>()

    private val messages = Messages()

    @Composable
    private fun Framed(content: @Composable () -> Unit) = TestFrame(messages = messages, content = content)

    @Composable
    private fun Screen(home: Home = Fixtures.demo, store: HomeStore = remember { Fixtures.store() }) =
        Framed { SettingsScreen(home, store, Fixtures.SERVER_URL, onSignOut = {}) }

    private fun back() = app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }

    private fun secrets(): Secrets = ViewModelProvider(app.activity)[Secrets::class.java]

    @Test
    fun aRowOpensItsPageAndBackReturnsToTheIndex() {
        app.setContent { Screen() }
        app.onNodeWithText("People").performClick()
        app.onNodeWithText("Add a person").assertExists()
        back()
        app.onNodeWithText("Appearance").assertExists()
        app.onNodeWithText("Add a person").assertDoesNotExist()
        assertTrue(!app.activity.isFinishing)
    }

    // The same saved state survives rotation and the process being killed.
    @Test
    fun theOpenPageAndWhatIsTypedSurviveRecreation() {
        val restoration = StateRestorationTester(app)
        restoration.setContent { Screen() }
        app.onNodeWithText("People").performClick()
        app.onAllNodes(hasSetTextAction()).onFirst().performTextInput("anna@example.com")
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithText("Add a person").assertExists()
        app.onNodeWithText("anna@example.com").assertExists()
    }

    @Test
    fun closingAPageForgetsItsSecretsAndDraft() {
        app.setContent { Screen() }
        app.onNodeWithText("API tokens").performClick()
        app.runOnUiThread { secrets().newToken = SettingsFixtures.newAgent }
        app.onNodeWithText(SettingsFixtures.newAgent.secret).assertExists()
        app.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Garage door")
        back()
        app.onNodeWithText("API tokens").performClick()
        app.onNodeWithText(SettingsFixtures.newAgent.secret).assertDoesNotExist()
        app.onNodeWithText("Garage door").assertDoesNotExist()
        assertEquals(null, secrets().newToken)
    }

    @Test
    fun formsCheckBeforeSendingAnything() {
        app.setContent { Screen(store = remember { Fixtures.refusingStore("Refused by the server") }) }
        app.onNodeWithText("People").performClick()
        app.onNodeWithText("Add person").performClick()
        // On the fields themselves, as TalkBack hears them.
        app.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter their email address or name.")).assertExists()
        app.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "The password needs at least 8 characters.")).assertExists()
        // Sent, it would have been refused next to the form.
        app.onNodeWithText("Refused by the server").assertDoesNotExist()
    }

    @Test
    fun aRefusalShowsNextToTheForm() {
        app.setContent {
            Framed { PasswordPage(remember { Fixtures.refusingStore("The current password is wrong.", "bad_request", 400) }.api, remember { Secrets() }, onBack = {}) }
        }
        app.onAllNodes(hasSetTextAction())[0].performTextInput("hunter2hunter")
        app.onAllNodes(hasSetTextAction())[1].performTextInput("correct horse battery")
        app.onNodeWithText("Change password").performClick()
        // Under the current password, on its field, as TalkBack hears it.
        app.onNodeWithText("The current password is wrong.", useUnmergedTree = true).assertExists()
        app.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "The current password is wrong.")).assertExists()
        assertEquals(null, messages.current)
    }

    @Test
    fun copyPutsTheTextOnTheClipboardAndSaysSo() {
        val home = SettingsFixtures.linked
        app.setContent {
            Framed {
                RemoteAccessPage(checkNotNull(home.app).tunnel, remember { Fixtures.store() }.api, remember { Secrets() }, onBack = {})
            }
        }
        app.onNodeWithText("Copy").performClick()
        val clip = checkNotNull(app.activity.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals(checkNotNull(home.app).tunnel.url, clip.getItemAt(0).text.toString())
        assertEquals("Address copied", messages.current?.text)
    }

    @Test
    fun aCopiedTokenIsMarkedSensitive() {
        app.setContent {
            Framed {
                TokensPage(
                    listOf(SettingsFixtures.agent),
                    remember { Fixtures.store() }.api,
                    remember { Secrets().apply { newToken = SettingsFixtures.newAgent } },
                    rememberTimeWords(),
                    Fixtures.SERVER_URL,
                    onChanged = {},
                    onBack = {},
                )
            }
        }
        app.onNodeWithText("Copy").performClick()
        val clip = checkNotNull(app.activity.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals(SettingsFixtures.newAgent.secret, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals("Token copied", messages.current?.text)
    }

    @Test
    fun removingSomeoneAsksFirst() {
        app.setContent {
            Framed {
                PeoplePage(SettingsFixtures.users, remember { Fixtures.store() }.api, remember { Secrets() }, rememberTimeWords(), onChanged = {}, onBack = {})
            }
        }
        // Only the other person can be removed.
        app.onNodeWithContentDescription("Remove demo").assertDoesNotExist()
        app.onNodeWithContentDescription("Remove anna@example.com").performClick()
        app.onNodeWithText("They are signed out and can no longer sign in.").assertExists()
        app.onNodeWithText("Cancel").performClick()
        app.onNodeWithText("They are signed out and can no longer sign in.").assertDoesNotExist()
        app.onAllNodesWithText("Added", substring = true).assertCountEquals(2)
    }

    @Test
    fun aProblemWithTheAccountIsPutRightByLinkingItAgain() {
        app.setContent {
            Framed {
                AccountPage(checkNotNull(SettingsFixtures.loginExpired.app), remember { Fixtures.acceptingStore() }.api, remember { Secrets() }, onBack = {})
            }
        }
        app.onNodeWithText("Send code").assertDoesNotExist()
        app.onNodeWithText("Link account again").performClick()
        app.onAllNodes(hasSetTextAction()).onFirst().performTextInput("you@example.com")
        app.onNodeWithText("Send code").performClick()
        app.onNodeWithText("Enter the code Roborock sent to you@example.com.").assertExists()
        app.onAllNodes(hasSetTextAction()).onFirst().performTextInput("482913")
        app.onNodeWithText("Link account").performClick()
        assertEquals("Your Roborock account is linked and the server is looking for its robots.", messages.current?.text)
        // Unlinking stays a separate choice, below.
        app.onNodeWithText("Unlink account").assertExists()
        app.onNodeWithText("Link account again").assertExists()
    }

    @Test
    fun beforeTheServerHasSaidAnythingItsSettingsWait() {
        app.setContent { Screen(Fixtures.connecting) }
        app.onNodeWithText("Roborock account").assertIsNotEnabled()
        app.onNodeWithText("Match device").assertIsNotEnabled()
        app.onAllNodesWithText("Waiting for the server").assertCountEquals(3)
    }
}
