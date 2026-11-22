package com.espotek.roborockathome.ui.schedules

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Schedules and its editor: build/shots/schedules-*.png and editor-*.png. */
@RunWith(RobolectricTestRunner::class)
class SchedulesShots {
    // -- the list --------------------------------------------------------------

    @Test fun list() = shot("schedules") { Screen() }

    @Test fun listSmall() = shot("schedules", ShotSize.SmallPhone) { Screen() }

    @Test fun listLargeText() = shot("schedules", fontScale = 2f) { Screen() }

    @Test fun listSwitching() = shot("schedules-switching", act = { switchGarage() }) { Screen() }

    @Test fun empty() = shot("schedules-empty") { Screen(Fixtures.demo.copy(schedules = emptyList())) }

    @Test fun notLinked() = shot("schedules-not-linked") { Screen(Fixtures.notLinked) }

    @Test fun noTimeZone() = shot("schedules-no-time-zone") {
        Screen(Fixtures.demo.copy(app = Fixtures.demo.app!!.copy(tz = null), schedules = emptyList()))
    }

    // -- the editor ------------------------------------------------------------

    @Test fun new() = shot("editor-new", act = { newSchedule() }) { Screen() }

    @Test fun newTall() = shot("editor-new", ShotSize.Tall, act = { newSchedule() }) { Screen() }

    @Test fun newSmall() = shot("editor-new", ShotSize.SmallPhone, act = { newSchedule() }) { Screen() }

    @Test fun newLargeText() = shot("editor-new", ShotSize.Tall, fontScale = 2f, act = { newSchedule() }) { Screen() }

    @Test fun changed() = shot("editor-changed", act = { changeBeforeGuests(); scrollTo("Delete") }) { Screen() }

    @Test fun changedTall() = shot("editor-changed", ShotSize.Tall, act = { changeBeforeGuests() }) { Screen() }

    @Test fun changedSmall() = shot("editor-changed", ShotSize.SmallPhone, act = { changeBeforeGuests(); scrollTo("Delete") }) { Screen() }

    @Test fun changedLargeText() = shot("editor-changed", fontScale = 2f, act = { changeBeforeGuests(); scrollTo("Delete") }) { Screen() }

    @Test fun pickRooms() = shot("editor-rooms", ShotSize.Tall, act = { newSchedule(); pickRooms() }) { Screen(Fixtures.groundFloorHome) }

    @Test fun pickRoomsLargeText() = shot("editor-rooms", ShotSize.Tall, fontScale = 2f, act = { newSchedule(); pickRooms() }) {
        Screen(Fixtures.groundFloorHome)
    }

    @Test fun delete() = shot("editor-delete", act = {
        openBeforeGuests()
        scrollTo("Delete").performClick()
    }) { Screen() }

    @Test fun deleteLargeText() = shot("editor-delete", ShotSize.SmallPhone, fontScale = 2f, act = {
        openBeforeGuests()
        scrollTo("Delete").performClick()
    }) { Screen() }

    @Test fun discard() = shot("editor-discard", act = {
        changeBeforeGuests()
        onNodeWithContentDescription("Back").performClick()
    }) { Screen() }

    @Test fun time() = shot("editor-time", act = {
        newSchedule()
        onNodeWithContentDescription("Time", substring = true).performClick()
    }) { Screen() }

    @Test fun timeSmall() = shot("editor-time", ShotSize.SmallPhone, act = {
        newSchedule()
        onNodeWithContentDescription("Time", substring = true).performClick()
    }) { Screen() }

    @Test fun timeLargeText() = shot("editor-time", fontScale = 2f, act = {
        newSchedule()
        onNodeWithContentDescription("Time", substring = true).performClick()
    }) { Screen() }

    // A phone set to 24 hours: the list, and the picker's 24-hour dial.
    @Test fun list24Hour() {
        use24Hour()
        shot("schedules-24h") { Screen() }
    }

    @Test fun time24Hour() {
        use24Hour()
        shot("editor-time-24h", act = {
            changeBeforeGuests()
            onNodeWithContentDescription("Time", substring = true).performScrollTo().performClick()
        }) { Screen() }
    }

    private fun use24Hour() =
        check(Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24"))

    @Test fun refused() = shot("editor-refused", act = {
        newSchedule()
        onNodeWithText("Save").performClick()
    }) { Screen(store = remember { Fixtures.refusingStore("Downstairs has no suction level 'max'.", "bad_request", 400) }) }

    @Test fun pickNothing() = shot("editor-pick-nothing", act = {
        newSchedule()
        onNodeWithText("Pick rooms").performClick()
        onNodeWithText("Save").performClick()
    }) { Screen() }

    @Test fun oneRobot() = shot("editor-one-robot", act = { newSchedule() }) { Screen(Fixtures.singleRobot) }

    @Composable
    private fun Screen(home: Home = Fixtures.demo, store: HomeStore = remember { Fixtures.store() }) =
        SchedulesScreen(home, store, rooms = null, onScheduling = {})
}

internal fun ComposeUiTest.newSchedule() {
    onNodeWithText("New schedule").performClick()
}

/** Opens "Before guests arrive": Downstairs on Fridays at 5 pm, Living room, Hall and Guest room. */
internal fun SemanticsNodeInteractionsProvider.openBeforeGuests() {
    val card = hasText("Living room, Hall, Guest room")
    onNode(hasScrollToNodeAction()).performScrollToNode(card)
    onNode(card).performClick()
}

/** Opens "Before guests arrive" and adds Saturday, so the page holds an unsaved change. */
internal fun SemanticsNodeInteractionsProvider.changeBeforeGuests() {
    openBeforeGuests()
    onNodeWithContentDescription("Saturday").performScrollTo().performClick()
}

internal fun ComposeUiTest.pickRooms() {
    onNodeWithText("Pick rooms").performScrollTo().performClick()
    for (room in listOf("Kitchen", "Kids' playroom and reading nook", "Unnamed room")) {
        onNodeWithText(room).performScrollTo().performClick()
    }
}

internal fun SemanticsNodeInteractionsProvider.scrollToGarage() {
    onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Garage schedule", substring = true))
}

internal fun ComposeUiTest.switchGarage() {
    scrollToGarage()
    onNodeWithContentDescription("Garage schedule", substring = true).performClick()
}

internal fun ComposeUiTest.scrollTo(text: String) = onNodeWithText(text).performScrollTo()
