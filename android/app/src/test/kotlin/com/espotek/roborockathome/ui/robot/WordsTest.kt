package com.espotek.roborockathome.ui.robot

import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.timeWords
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for Android's ICU, which writes the times.
@RunWith(RobolectricTestRunner::class)
class WordsTest {
    private val docked = Fixtures.downstairsDocked

    @Test
    fun eachActivityOffersThePagesActionsMainOneFirst() {
        fun offered(activity: Activity) = actionsFor(docked.copy(activity = activity))
        assertEquals(listOf(RobotAction.Start), offered(Activity.Docked))
        assertEquals(listOf(RobotAction.Start, RobotAction.Dock), offered(Activity.Idle))
        assertEquals(listOf(RobotAction.Pause, RobotAction.Stop, RobotAction.Dock), offered(Activity.Cleaning))
        assertEquals(listOf(RobotAction.Resume, RobotAction.Dock, RobotAction.Stop), offered(Activity.Paused))
        assertEquals(listOf(RobotAction.Pause, RobotAction.Stop), offered(Activity.Returning))
        assertEquals(listOf(RobotAction.Resume, RobotAction.Dock), offered(Activity.Error))
        assertEquals(emptyList<RobotAction>(), offered(Activity.Offline))
        assertEquals("Clean whole home", RobotAction.Start.label)
        assertEquals("Go to dock", RobotAction.Dock.label)
    }

    @Test
    fun withItsBatteryLowGoingToTheDockLeads() {
        // Studio is idle far from its dock with 4%.
        assertEquals(listOf(RobotAction.Dock, RobotAction.Start), actionsFor(Fixtures.studioLowBattery))
        val cleaning = Fixtures.studioLowBattery.copy(activity = Activity.Cleaning)
        assertEquals(listOf(RobotAction.Dock, RobotAction.Pause, RobotAction.Stop), actionsFor(cleaning))
        // On a dock that has lost power there is no going to it, and nothing else changes.
        assertEquals(listOf(RobotAction.Start), actionsFor(Fixtures.studioLowBattery.copy(activity = Activity.Docked)))
    }

    @Test
    fun theBannerSaysWhereAndWhenFromTheFacts() {
        val words = timeWords()
        val garage = Fixtures.garageJammed
        val jam = checkNotNull(garage.attention)
        assertEquals("Last seen in Workshop at 4:45 PM with 40% battery.", attentionDetail(garage, jam, words))
        assertEquals("Last seen at 4:45 PM with 40% battery.", attentionDetail(garage, jam.copy(room = null), words))
        val studio = Fixtures.studioLowBattery
        val low = checkNotNull(studio.attention)
        assertEquals("The robot is in Bedroom with 4% battery and is not charging.", attentionDetail(studio, low, words))
        assertEquals("The robot has 4% battery and is not charging.", attentionDetail(studio, low.copy(room = null), words))
        assertEquals("The robot is in Bedroom with 4% battery and is charging.", attentionDetail(studio.copy(charging = true), low, words))
        val offline = Fixtures.studioOffline
        assertEquals(
            "Last seen in Bedroom ${words.at(checkNotNull(offline.pos).ts)} with 4% battery.",
            attentionDetail(offline, checkNotNull(offline.attention), words),
        )
    }

    @Test
    fun aRobotThatDoesNotAnswerIsOfferedNothingWhateverItLastReported() {
        assertEquals(emptyList<RobotAction>(), actionsFor(Fixtures.upstairsCleaning.copy(online = false)))
        assertEquals(emptyList<RobotAction>(), actionsFor(Fixtures.studioOffline))
    }

    @Test
    fun wordsForTheSwitcherAndTheSlot() {
        assertEquals("Charging", activityWord(docked))
        assertEquals("Docked", activityWord(docked.copy(charging = false)))
        assertEquals("Going home", activityWord(docked.copy(activity = Activity.Returning)))
        assertEquals("Needs help", activityWord(Fixtures.garageJammed))
        assertEquals("Clean 1 room", cleanRoomsLabel(1))
        assertEquals("Clean 14 rooms", cleanRoomsLabel(14))
    }

    @Test
    fun areasAsThePageWritesThem() {
        assertEquals("77.1 m²", area(77.1, Locale.US))
        assertEquals("15,7 m²", area(15.7, Locale.GERMANY))
    }

    @Test
    fun theLastSeenCaptionSaysTheDayOnlyWhenItWasNotToday() {
        val words = timeWords()
        val now = Fixtures.clock.instant().atZone(Fixtures.clock.zone)
        fun at(daysBefore: Long, hour: Int, minute: Int) = now.minusDays(daysBefore).withHour(hour).withMinute(minute).toEpochSecond()
        assertEquals("Last seen 4:45\u202FPM", words.lastSeen(Fixtures.garageJammed.pos!!.ts))
        assertEquals("Last seen Yesterday 7:12\u202FPM", words.lastSeen(at(1, 19, 12)))
        assertEquals("Last seen Tue 7:12\u202FPM", words.lastSeen(at(5, 19, 12)))
        assertEquals("Last seen Nov 12 7:12\u202FPM", words.lastSeen(at(10, 19, 12)))
    }
}
