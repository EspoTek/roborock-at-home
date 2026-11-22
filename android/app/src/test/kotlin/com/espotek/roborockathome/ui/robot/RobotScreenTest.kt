package com.espotek.roborockathome.ui.robot

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.TestFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RobotScreenTest {
    @get:Rule
    val app = createAndroidComposeRule<ComponentActivity>()

    private var home by mutableStateOf(Fixtures.demo)

    private fun show(start: Home, store: () -> HomeStore = Fixtures::store) {
        home = start
        app.setContent { TestFrame { RobotInShell(home, remember { store() }) } }
    }

    private fun tab(robot: String) = app.onNode(hasContentDescription("$robot,", substring = true))

    private val inProgress = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress")

    @Test
    fun aTapOnARoomPicksItAndASecondUnpicksIt() {
        show(Fixtures.groundFloorHome)
        app.onNodeWithText("Clean whole home").assertExists()
        app.tapRooms(Fixtures.groundFloor, 19)
        app.onNodeWithText("Kitchen").assertIsSelected()
        app.onNodeWithText("Clean 1 room").assertExists()
        app.onNodeWithText("Clean whole home").assertDoesNotExist()
        app.tapRooms(Fixtures.groundFloor, 19)
        app.onNodeWithText("Kitchen").assertIsNotSelected()
        app.onNodeWithText("Clean whole home").assertExists()
    }

    @Test
    fun onlyATapThatPicksARoomOnThePlanTicks() {
        lateinit var host: View
        home = Fixtures.groundFloorHome
        app.setContent {
            host = LocalView.current
            TestFrame { RobotInShell(home, remember { Fixtures.store() }) }
        }
        app.onNodeWithText("Kitchen").performScrollTo().performClick()
        app.onNodeWithText("Kitchen").performClick()
        app.onNodeWithContentDescription(PLAN).performScrollTo().performTouchInput { click(Offset(8f, 8f)) }
        assertNotEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(host).lastHapticFeedbackPerformed())
        app.tapRooms(Fixtures.groundFloor, 19)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(host).lastHapticFeedbackPerformed())
    }

    @Test
    fun aFingerThatWandersALittleOrStaysLongIsStillATap() {
        show(Fixtures.groundFloorHome)
        val plan = app.onNodeWithContentDescription(PLAN)
        val at = app.roomOnScreen(Fixtures.groundFloor, 19)
        val wander = with(app.density) { 10.dp.toPx() }
        plan.performTouchInput {
            down(at)
            moveBy(Offset(wander, 0f))
            advanceEventTime(3000)
            up()
        }
        app.onNodeWithText("Kitchen").assertIsSelected()
        // Once a drag has gone further than a tap may, coming back to the start is not a tap.
        plan.performTouchInput {
            down(at)
            moveBy(Offset(wander * 3, 0f))
            moveBy(Offset(-wander * 3, 0f))
            up()
        }
        app.onNodeWithText("Kitchen").assertIsSelected()
    }

    @Test
    fun aPinchNeverBecomesATapWhenAFingerLifts() {
        show(Fixtures.groundFloorHome)
        val plan = app.onNodeWithContentDescription(PLAN)
        plan.performTouchInput {
            down(0, center)
            down(1, center + Offset(40f, 0f))
            moveBy(1, Offset(60f, 0f))
            up(1)
            up(0)
        }
        app.onNodeWithText("Clean whole home").assertExists()
    }

    @Test
    fun aDragOnTheFittedPlanScrollsThePage() {
        show(Fixtures.groundFloorHome)
        val plan = app.onNodeWithContentDescription(PLAN)
        val top = plan.fetchSemanticsNode().positionInRoot.y
        plan.performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        app.waitForIdle()
        val now = plan.fetchSemanticsNode().positionInRoot.y
        assertTrue("The page should have scrolled with the drag, but the plan went from $top to $now", now < top - 250f)
    }

    @Test
    fun aDragOnAZoomedPlanPansItAndThePageStaysPut() {
        show(Fixtures.groundFloorHome)
        zoomIn(3)
        val plan = app.onNodeWithContentDescription(PLAN)
        val top = plan.fetchSemanticsNode().positionInRoot.y
        plan.performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        app.waitForIdle()
        assertEquals(top, plan.fetchSemanticsNode().positionInRoot.y, 0.5f)
        // Recentre gives the drags back to the page.
        app.onNodeWithContentDescription("Recentre the map").performClick()
        app.waitForIdle()
        plan.performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        app.waitForIdle()
        assertTrue(plan.fetchSemanticsNode().positionInRoot.y < top - 250f)
    }

    @Test
    fun aZoomedPlanAtItsBottomEdgeLetsADragUpScrollThePage() {
        show(Fixtures.groundFloorHome)
        zoomIn(3)
        val plan = app.onNodeWithContentDescription(PLAN)
        val top = plan.fetchSemanticsNode().positionInRoot.y
        // One long drag up pans the plan as far as it goes, until its bottom edge is in the window, and
        // the page stays put.
        plan.performTouchInput {
            down(center)
            moveBy(Offset(0f, -30f))
            moveBy(Offset(0f, -3000f))
            up()
        }
        app.waitForIdle()
        assertEquals(top, plan.fetchSemanticsNode().positionInRoot.y, 0.5f)
        // At its edge the plan cannot move up, so the next drag up scrolls the page.
        plan.performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        app.waitForIdle()
        assertTrue(plan.fetchSemanticsNode().positionInRoot.y < top - 250f)
    }

    private fun zoomIn(times: Int) {
        repeat(times) { app.onNodeWithContentDescription("Zoom in").performClick() }
        app.waitForIdle()
    }

    @Test
    fun picksAreKeptPerRobotThroughASwitchAndBack() {
        show(Fixtures.demo)
        app.onNodeWithText("Kitchen").performScrollTo().performClick()
        app.onNodeWithText("Clean 1 room").assertExists()
        app.choose("Upstairs")
        app.onNodeWithText("Clean 1 room").assertDoesNotExist()
        app.onNodeWithText("Pause").assertExists()
        app.choose("Downstairs")
        app.onNodeWithText("Kitchen").assertIsSelected()
        app.onNodeWithText("Clean 1 room").assertExists()
    }

    @Test
    fun theRobotChosenAndItsPicksSurviveRecreation() {
        val restoration = StateRestorationTester(app)
        home = Fixtures.demo
        restoration.setContent { TestFrame { RobotInShell(home, remember { Fixtures.store() }) } }
        app.choose("Garage")
        app.onNodeWithText("Workshop").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        tab("Garage").assertIsSelected()
        app.onNodeWithText("Workshop").assertIsSelected()
        app.onNodeWithText("Clean 1 room").assertExists()
    }

    @Test
    fun whenTheChosenRobotGoesTheFirstIsChosen() {
        show(Fixtures.demo)
        app.choose("Garage")
        tab("Garage").assertIsSelected()
        home = Fixtures.demo.copy(robots = Fixtures.demo.robots.filter { it.id != "garage" })
        tab("Downstairs").assertIsSelected()
        // And stays chosen when the other robot comes back.
        home = Fixtures.demo
        tab("Downstairs").assertIsSelected()
    }

    @Test
    fun anAcceptedRoomCleanClearsThePicksAndThePasses() {
        show(Fixtures.groundFloorHome, Fixtures::acceptingStore)
        app.tapRooms(Fixtures.groundFloor, 17, 19)
        app.onNodeWithContentDescription("Two passes").performClick()
        app.onNodeWithText("Clean 2 rooms").performClick()
        app.onNodeWithText("Clean whole home").assertExists()
        app.onNodeWithText("Kitchen").assertIsNotSelected()
        app.tapRooms(Fixtures.groundFloor, 17)
        app.onNodeWithContentDescription("One pass").assertIsSelected()
    }

    @Test
    fun aRefusedRoomCleanKeepsThePicksAndSaysWhy() {
        show(Fixtures.groundFloorHome) { Fixtures.refusingStore("Ground floor did not answer") }
        app.tapRooms(Fixtures.groundFloor, 17, 19)
        app.onNodeWithContentDescription("Three passes").performClick()
        app.onNodeWithText("Clean 2 rooms").performClick()
        app.onNodeWithText("Ground floor did not answer").assertExists()
        app.onNodeWithText("Kitchen").assertIsSelected()
        app.onNodeWithContentDescription("Three passes").assertIsSelected()
    }

    @Test
    fun aCommandStaysBusyUntilAnswered() {
        show(Fixtures.cleaningRooms)
        app.onNodeWithText("Pause").performClick()
        app.onNodeWithText("Pause").assert(inProgress)
        // Another robot's controls are its own.
        app.choose("Upstairs")
        app.onNodeWithText("Pause").assert(inProgress.not())
        app.choose("Downstairs")
        app.onNodeWithText("Pause").assert(inProgress)
    }

    @Test
    fun aLevelIsPendingUntilTheRobotReportsIt() {
        show(Fixtures.cleaningRooms, Fixtures::acceptingStore)
        app.onNodeWithText("Turbo").performScrollTo().performClick()
        app.onNodeWithText("Turbo").assertIsSelected().assert(inProgress)
        home = home.copy(robots = home.robots.map { if (it.id == "downstairs") it.copy(fan = "turbo") else it })
        app.onNodeWithText("Turbo").assertIsSelected().assert(inProgress.not())
    }

    @Test
    fun scheduleTheseRoomsOpensANewScheduleWithTheRoomsAndPasses() {
        show(Fixtures.groundFloorHome)
        app.onNodeWithText("Schedule these rooms").assertDoesNotExist()
        app.tapRooms(Fixtures.groundFloor, 17, 19)
        app.onNodeWithContentDescription("Two passes").performClick()
        app.onNodeWithText("Schedule these rooms").performScrollTo().performClick()
        app.onNodeWithText("New schedule").assertExists()
        app.onNodeWithText("Pick rooms").assertIsSelected()
        app.onNodeWithText("Living room").performScrollTo().assertIsSelected()
        app.onNodeWithText("Kitchen").assertIsSelected()
        app.onNodeWithText("Dining room").assertIsNotSelected()
        app.onNodeWithContentDescription("Two passes").assertIsSelected()
    }

    @Test
    fun locateSaysTheRobotIsPlayingASound() {
        show(Fixtures.demo, Fixtures::acceptingStore)
        app.onNodeWithText("Locate").performScrollTo().performClick()
        app.onNodeWithText("Downstairs is playing a sound.").assertExists()
    }

    @Test
    fun aRobotThatDoesNotAnswerKeepsItsPlanAndRoomsButTakesNoCommands() {
        show(Fixtures.offlineRobot)
        app.choose("Studio")
        app.onNodeWithText("Clean whole home").assertDoesNotExist()
        app.onNodeWithText("Locate").assertIsNotEnabled()
        app.onNodeWithText("Bedroom").performScrollTo().performClick()
        app.onNodeWithText("Bedroom").assertIsSelected()
        app.onNodeWithText("Clean 1 room").assertIsNotEnabled()
    }
}
