package com.espotek.roborockathome.ui.shell

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.espotek.roborockathome.MainActivity
import com.espotek.roborockathome.data.Preferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The app from its launcher activity, with nobody signed in, so nothing reaches for a network.
@RunWith(RobolectricTestRunner::class)
class AppTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theFirstLaunchAsksForTheServerWithAnEmptyField() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Server address").assertExists()
            compose.onNodeWithText("For example, 192.168.1.50:8770").assertExists()
            compose.onAllNodes(hasSetTextAction())[0].assert(hasText(""))
        }
    }

    @Test
    fun theAddressAndNameLastSignedInWithAreFilledIn() {
        val kept = Preferences(RuntimeEnvironment.getApplication())
        kept.serverUrl = "http://192.168.1.50:8770"
        kept.name = "demo"
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasSetTextAction() and hasText("http://192.168.1.50:8770")).assertExists()
            compose.onNode(hasSetTextAction() and hasText("demo")).assertExists()
        }
    }

    @Test
    fun whatWasTypedSurvivesRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fields = compose.onAllNodes(hasSetTextAction())
            fields[0].performTextInput("10.0.2.2:8793")
            fields[1].performTextInput("demo")
            fields[2].performTextInput("a password")
            scenario.recreate()
            compose.onNode(hasSetTextAction() and hasText("10.0.2.2:8793")).assertExists()
            compose.onNode(hasSetTextAction() and hasText("demo")).assertExists()
            // Rotation keeps the password too; only the process being killed loses it.
            compose.onNodeWithContentDescription("Show password").performClick()
            compose.onNode(hasSetTextAction() and hasText("a password")).assertExists()
        }
    }
}
