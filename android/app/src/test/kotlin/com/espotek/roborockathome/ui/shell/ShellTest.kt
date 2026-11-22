package com.espotek.roborockathome.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.TestFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShellTest {
    @get:Rule
    val app = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backReturnsToRobotAndThenLeaves() {
        app.setContent { TestFrame { Shell(Fixtures.demo, remember { Fixtures.store() }, Fixtures.SERVER_URL, onSignOut = {}) } }
        app.onNodeWithText("Settings").performClick()
        app.onNodeWithText("Appearance").assertExists()

        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        app.onNodeWithContentDescription("Floor plan. Tap a room to select it.").assertExists()
        assertFalse(app.activity.isFinishing)

        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(app.activity.isFinishing)
    }

    // The same saved state survives rotation and the process being killed.
    @Test
    fun destinationSurvivesRecreation() {
        val restoration = StateRestorationTester(app)
        restoration.setContent { TestFrame { Shell(Fixtures.demo, remember { Fixtures.store() }, Fixtures.SERVER_URL, onSignOut = {}) } }
        app.onNodeWithText("Schedules").performClick()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithText("New schedule").assertExists()
    }

    @Test
    fun theScheduleEditorHidesTheBottomBarUntilItCloses() {
        app.setContent { TestFrame { Shell(Fixtures.demo, remember { Fixtures.store() }, Fixtures.SERVER_URL, onSignOut = {}) } }
        app.onNodeWithText("Schedules").performClick()
        app.onNodeWithText("New schedule").performClick()
        app.onNodeWithText("Activity").assertDoesNotExist()
        app.waitForIdle()
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
        app.onNodeWithText("Activity").assertExists()
        app.onNodeWithText("New schedule").assertExists()
    }

    @Test
    fun connectingToServerShowsUntilTheStreamIsLive() {
        var home by mutableStateOf(Fixtures.connecting)
        app.setContent { TestFrame { Shell(home, remember { Fixtures.store() }, Fixtures.SERVER_URL, onSignOut = {}) } }
        app.onNodeWithText("Connecting to server").assertExists()
        home = Fixtures.demo
        app.onNodeWithText("Connecting to server").assertDoesNotExist()
        home = Fixtures.demo.copy(link = Link.Reconnecting)
        app.onNodeWithText("Connecting to server").assertExists()
    }
}
