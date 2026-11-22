package com.espotek.roborockathome.ui.robot

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.espotek.roborockathome.core.model.Account
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Robot screen in the shell: build/shots/robot-*.png, light and dark. */
@RunWith(RobolectricTestRunner::class)
class RobotScreenShots {
    // What the designer asked for first: cleaning with its path and every action; the 14-room plan
    // with two rooms picked; a fault; an offline robot; one robot against four.
    @Test fun cleaning() = robot("robot-cleaning", Fixtures.cleaningRooms)
    @Test fun roomsPicked() = robot("robot-rooms-picked", Fixtures.groundFloorHome) { tapRooms(Fixtures.groundFloor, 17, 19) }
    @Test fun fault() = robot("robot-fault", Fixtures.demo) { choose("Garage") }
    @Test fun offline() = robot("robot-offline", Fixtures.offlineRobot) { choose("Studio") }
    @Test fun oneRobot() = robot("robot-one", Fixtures.singleRobot)
    @Test fun fourRobots() = robot("robot-four", Fixtures.demo)

    // The same at 360 x 640 and at twice the text size, and one on its side.
    @Test fun cleaningSmall() = robot("robot-cleaning", Fixtures.cleaningRooms, ShotSize.SmallPhone)
    @Test fun roomsPickedSmall() = robot("robot-rooms-picked", Fixtures.groundFloorHome, ShotSize.SmallPhone) { tapRooms(Fixtures.groundFloor, 17, 19) }
    @Test fun faultSmall() = robot("robot-fault", Fixtures.demo, ShotSize.SmallPhone) { choose("Garage") }
    @Test fun offlineSmall() = robot("robot-offline", Fixtures.offlineRobot, ShotSize.SmallPhone) { choose("Studio") }
    @Test fun cleaningLarge() = robot("robot-cleaning", Fixtures.cleaningRooms, fontScale = 2f)
    @Test fun roomsPickedLarge() = robot("robot-rooms-picked", Fixtures.groundFloorHome, fontScale = 2f) { tapRooms(Fixtures.groundFloor, 17, 19) }
    @Test fun faultLarge() = robot("robot-fault", Fixtures.demo, fontScale = 2f) { choose("Garage") }
    @Test fun offlineLarge() = robot("robot-offline", Fixtures.offlineRobot, fontScale = 2f) { choose("Studio") }
    @Test fun fourRobotsLarge() = robot("robot-four", Fixtures.demo, fontScale = 2f)
    @Test fun cleaningLandscape() = robot("robot-cleaning", Fixtures.cleaningRooms, ShotSize.Landscape)

    // The rest of the page, and the states between.
    @Test fun roomsAndLevels() = robot("robot-rooms-levels", Fixtures.groundFloorHome) {
        tapRooms(Fixtures.groundFloor, 17, 19, 27)
        onNodeWithText("Locate").performScrollTo()
    }
    @Test fun roomsAndLevelsLarge() = robot("robot-rooms-levels", Fixtures.groundFloorHome, fontScale = 2f) {
        tapRooms(Fixtures.groundFloor, 17, 19, 27)
        onNodeWithText("Suction").performScrollTo()
    }
    @Test fun lowBattery() = robot("robot-low-battery", Fixtures.demo) { choose("Studio") }
    @Test fun scheduleRooms() = robot("robot-schedule-rooms", Fixtures.groundFloorHome) {
        tapRooms(Fixtures.groundFloor, 17, 19)
        onNodeWithText("Schedule these rooms").performScrollTo()
    }
    @Test fun scheduleRoomsLarge() = robot("robot-schedule-rooms", Fixtures.groundFloorHome, fontScale = 2f) {
        tapRooms(Fixtures.groundFloor, 17, 19)
        onNodeWithText("Schedule these rooms").performScrollTo()
    }
    @Test fun offlineCaptionLarge() = robot("robot-offline-zoomed", Fixtures.offlineRobot, ShotSize.SmallPhone, 2f) {
        choose("Studio")
        onNodeWithText("Show on map").performClick()
    }
    @Test fun busy() = robot("robot-busy", Fixtures.cleaningRooms) { onNodeWithText("Pause").performClick() }
    @Test fun levelPending() = robot("robot-level-pending", Fixtures.cleaningRooms) {
        onNodeWithText("Turbo").performScrollTo().performClick()
        onNodeWithText("Locate").performClick()
    }
    @Test fun refused() = robot("robot-refused", Fixtures.cleaningRooms, store = { Fixtures.refusingStore("Downstairs did not answer") }) {
        onNodeWithText("Pause").performClick()
    }
    @Test fun zoomedIn() = robot("robot-zoomed", Fixtures.cleaningRooms) {
        repeat(3) { onNodeWithContentDescription("Zoom in").performClick() }
    }
    // Zoomed in and dragged far left and up in one go: the plan stops at its bottom right corner.
    @Test fun zoomedAndPanned() = robot("robot-zoomed-panned", Fixtures.cleaningRooms) {
        repeat(3) { onNodeWithContentDescription("Zoom in").performClick() }
        onNodeWithContentDescription(PLAN).performTouchInput {
            down(center)
            moveBy(Offset(-30f, -20f))
            moveBy(Offset(-1500f, -1000f))
            up()
        }
    }
    // Shown on the map at 2x text, then dragged up: one drag pans the plan to its bottom edge, and the
    // next scrolls the page, bringing the status and Recentre into view.
    @Test fun offlineZoomedThenScrolled() = robot("robot-offline-zoomed-scrolled", Fixtures.offlineRobot, ShotSize.SmallPhone, 2f) {
        choose("Studio")
        onNodeWithText("Show on map").performClick()
        val plan = onNodeWithContentDescription(PLAN)
        plan.performTouchInput {
            down(center)
            moveBy(Offset(0f, -30f))
            moveBy(Offset(0f, -3000f))
            up()
        }
        plan.performTouchInput { swipeUp(startY = bottom - 1f, endY = top + 1f, durationMillis = 1000) }
    }
    @Test fun showOnMap() = robot("robot-show-on-map", Fixtures.demo) {
        choose("Garage")
        onNodeWithText("Show on map").performClick()
    }
    @Test fun planOnItsWay() = robot("robot-plan-loading", Fixtures.demo.copy(plans = emptyMap()))
    @Test fun noMapYet() = robot("robot-no-map", Fixtures.demo.copy(robots = listOf(Fixtures.downstairsDocked.copy(map = null, pos = null)) + Fixtures.demo.robots.drop(1)))
    @Test fun notLinked() = robot("robot-not-linked", Fixtures.notLinked)
    @Test fun noRobotsFound() = robot(
        "robot-none-found",
        Fixtures.notLinked.copy(app = Fixtures.notLinked.app!!.copy(account = Account(linked = true, email = "anna@example.com", problem = null))),
    )

    private fun robot(
        name: String,
        home: Home,
        size: ShotSize = ShotSize.Phone,
        fontScale: Float = 1f,
        store: () -> HomeStore = Fixtures::store,
        act: ComposeUiTest.() -> Unit = {},
    ) = shot(name, size, fontScale, act) { RobotInShell(home, remember { store() }) }
}
