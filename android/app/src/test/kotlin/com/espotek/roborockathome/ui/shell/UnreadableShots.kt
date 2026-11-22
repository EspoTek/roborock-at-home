package com.espotek.roborockathome.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What the store says when the server sends an activity this version does not know (core's HomeStoreTest).
private const val PROBLEM = "The server sent the state of the home this app cannot read: " +
    "com.espotek.roborockathome.core.model.Activity does not contain element with name 'mopping' at path \$.robots[0].activity."

/** The screen for a server this version of the app cannot read: build/shots/unreadable-*.png. */
@RunWith(RobolectricTestRunner::class)
class UnreadableShots {
    @Test fun phone() = shot("unreadable") { Screen() }

    @Test fun smallPhone() = shot("unreadable", ShotSize.SmallPhone) { Screen() }

    @Test fun largeText() = shot("unreadable", fontScale = 2f) { Screen() }

    @Test fun details() = shot("unreadable-details", act = { openDetails() }) { Screen() }

    @Test fun detailsSmallPhone() = shot("unreadable-details", ShotSize.SmallPhone, act = { openDetails() }) { Screen() }

    private fun ComposeUiTest.openDetails() {
        onNodeWithText("Technical details").performClick()
        onNodeWithText("Copy").performScrollTo()
    }

    @Composable
    private fun Screen() = UnreadableScreen(PROBLEM, onTryAgain = {}, onSignOut = {})
}
