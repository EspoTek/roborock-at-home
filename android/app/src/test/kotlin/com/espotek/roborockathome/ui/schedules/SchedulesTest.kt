package com.espotek.roborockathome.ui.schedules

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.TestFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The designer's reference phone, as the screenshots are drawn.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-port-xhdpi")
class SchedulesTest {
    @get:Rule
    val app = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Screen(home: Home = Fixtures.demo, store: HomeStore = remember { Fixtures.store() }) {
        TestFrame { SchedulesScreen(home, store, rooms = null, onScheduling = {}) }
    }

    // Once the last tap has been drawn, so the page it opened has its Back handler in place.
    private fun back() {
        app.waitForIdle()
        app.runOnUiThread { app.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun openBeforeGuests() = app.openBeforeGuests()

    private fun saturday() = app.onNodeWithContentDescription("Saturday").performScrollTo()

    private fun runNow() = app.onNodeWithText("Run now").performScrollTo()

    @Test
    fun theDraftSurvivesRotationAndTheProcessBeingKilled() {
        val restoration = StateRestorationTester(app)
        restoration.setContent { Screen() }
        openBeforeGuests()
        saturday().performClick()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithText("Edit schedule").assertExists()
        saturday().assertIsOn()
        app.onNodeWithText("Save changes to run this schedule").assertExists()
    }

    @Test
    fun runNowRunsOnlyWhatIsSaved() {
        app.setContent { Screen() }
        openBeforeGuests()
        runNow().assertIsEnabled()
        app.onNodeWithText("Save changes to run this schedule").assertDoesNotExist()
        saturday().performClick()
        runNow().assertIsNotEnabled()
        app.onNodeWithText("Save changes to run this schedule").assertExists()
        // Taking the change back leaves nothing unsaved.
        saturday().performClick()
        runNow().assertIsEnabled()
        app.onNodeWithText("Save changes to run this schedule").assertDoesNotExist()
    }

    @Test
    fun backWithoutChangesReturnsToTheList() {
        app.setContent { Screen() }
        openBeforeGuests()
        back()
        app.onNodeWithText("New schedule").assertExists()
    }

    @Test
    fun backWithChangesAsksAndCancellingKeepsTheDraft() {
        app.setContent { Screen() }
        openBeforeGuests()
        saturday().performClick()
        back()
        app.onNodeWithText("Discard your changes?").assertExists()
        app.onNodeWithText("Cancel").performClick()
        app.onNodeWithText("Discard your changes?").assertDoesNotExist()
        saturday().assertIsOn()

        back()
        app.onNodeWithText("Discard").performClick()
        app.onNodeWithText("New schedule").assertExists()
        // Opened again, it is the saved schedule.
        openBeforeGuests()
        saturday().assertIsOff()
    }

    @Test
    fun cancellingDeleteReturnsToTheDraftUntouched() {
        app.setContent { Screen() }
        openBeforeGuests()
        saturday().performClick()
        app.onNodeWithText("Delete").performScrollTo().performClick()
        // It describes the schedule as saved, Fridays only.
        app.onNodeWithText("Fri at 5:00", substring = true).assertExists()
        app.onNodeWithText("Cancel").performClick()
        app.onNodeWithText("Delete this schedule?").assertDoesNotExist()
        app.onNodeWithText("Edit schedule").assertExists()
        saturday().assertIsOn()
    }

    @Test
    fun aRefusedSaveIsShownOnThePageUntilTheDraftChanges() {
        app.setContent { Screen(store = remember { Fixtures.refusingStore("Downstairs has no suction level 'max'.", "bad_request", 400) }) }
        app.onNodeWithText("New schedule").performClick()
        app.onNodeWithText("Save").performClick()
        app.onNodeWithText("Downstairs has no suction level 'max'.").assertExists()
        app.onNodeWithText("New schedule").assertExists()
        saturday().performClick()
        app.onNodeWithText("Downstairs has no suction level 'max'.").assertDoesNotExist()
    }

    @Test
    fun aSaveNeedsADay() {
        app.setContent { Screen() }
        app.onNodeWithText("New schedule").performClick()
        app.onNodeWithText("Weekdays").performClick()
        for (day in listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) {
            app.onNodeWithContentDescription(day).performScrollTo().performClick()
        }
        app.onNodeWithText("Save").performClick()
        app.onNodeWithText("Pick at least one day.").assertExists()
    }

    @Test
    fun theSwitchIsPendingUntilTheServerAnswers() {
        app.setContent { Screen() }
        app.scrollToGarage()
        val garage = app.onNodeWithContentDescription("Garage schedule", substring = true)
        garage.assertIsOff()
        garage.performClick()
        garage.assertIsOn()
        garage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On, in progress"))
        // The editor did not open: the switch only switches.
        app.onNodeWithText("New schedule").assertExists()
    }
}
