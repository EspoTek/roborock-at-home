package com.espotek.roborockathome.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Activity: build/shots/activity-*.png. */
@RunWith(RobolectricTestRunner::class)
class ActivityShots {
    @Test fun threeDays() = shot("activity") { Screen() }

    @Test fun threeDaysTall() = shot("activity", ShotSize.Tall) { Screen() }

    @Test fun small() = shot("activity", ShotSize.SmallPhone) { Screen() }

    @Test fun largeText() = shot("activity", fontScale = 2f) { Screen() }

    @Test fun oneRobot() = shot("activity-one-robot") { Screen(Fixtures.singleRobot) }

    @Test fun empty() = shot("activity-empty") { Screen(Fixtures.demo.copy(history = emptyList())) }

    @Test fun emptyLargeText() = shot("activity-empty", ShotSize.SmallPhone, fontScale = 2f) { Screen(Fixtures.demo.copy(history = emptyList())) }

    @Test fun loading() = shot("activity-loading") { Screen(Fixtures.demo.copy(history = null)) }

    @Composable
    private fun Screen(home: Home = Fixtures.demo) = ActivityScreen(home, remember { Fixtures.store() })
}
