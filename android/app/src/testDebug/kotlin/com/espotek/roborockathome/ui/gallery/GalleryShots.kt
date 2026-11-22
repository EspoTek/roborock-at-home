package com.espotek.roborockathome.ui.gallery

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.holdDown
import com.espotek.roborockathome.shots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The kit gallery as pictures: build/shots/gallery-*.png, each page at normal text and at 2x. */
@RunWith(RobolectricTestRunner::class)
class GalleryShots {
    @Test fun buttons() = page(GalleryPage.Buttons)
    @Test fun buttonsLargeText() = page(GalleryPage.Buttons, large = true)
    @Test fun icons() = page(GalleryPage.Icons)
    @Test fun iconsLargeText() = page(GalleryPage.Icons, large = true)
    @Test fun choices() = page(GalleryPage.Choices)
    @Test fun choicesLargeText() = page(GalleryPage.Choices, large = true)
    @Test fun switches() = page(GalleryPage.Switches)
    @Test fun switchesLargeText() = page(GalleryPage.Switches, large = true)
    @Test fun settingsIndex() = page(GalleryPage.SettingsIndex)
    @Test fun settingsIndexLargeText() = page(GalleryPage.SettingsIndex, large = true)
    @Test fun fields() = page(GalleryPage.Fields)
    @Test fun fieldsLargeText() = page(GalleryPage.Fields, large = true)
    @Test fun feedback() = page(GalleryPage.Feedback)
    @Test fun feedbackLargeText() = page(GalleryPage.Feedback, large = true)
    @Test fun frame() = page(GalleryPage.Frame)
    @Test fun frameLargeText() = page(GalleryPage.Frame, large = true)

    @Test fun bottomBar() = page(GalleryPage.BottomBar)
    @Test fun bottomBarLargeText() = page(GalleryPage.BottomBar, large = true)

    // The narrowest phone at 2x text, where four labels cannot fit across and the bar goes to two rows.
    @Test fun bottomBarSmallPhoneLargeText() = shot("gallery-bottom-bar", ShotSize.SmallPhone, fontScale = 2f) {
        GalleryPageFrame(GalleryPage.BottomBar)
    }

    @Test fun dialog() = shot("gallery-dialog") { DialogPage(startOpen = true) }
    @Test fun dialogLargeText() = shot("gallery-dialog", fontScale = 2f) { DialogPage(startOpen = true) }

    // At 2x text a page runs past one phone screen, so it is drawn tall.
    private fun page(page: GalleryPage, large: Boolean = false) = shot(
        name = "gallery-" + page.name.replace(Regex("(?<=.)(?=[A-Z])"), "-").lowercase(),
        size = if (large) ShotSize.Tall else ShotSize.Phone,
        fontScale = if (large) 2f else 1f,
        act = { show(page) },
    ) { GalleryPageFrame(page) }

    private fun ComposeUiTest.show(page: GalleryPage) {
        when (page) {
            GalleryPage.Buttons, GalleryPage.Icons, GalleryPage.Choices, GalleryPage.Switches,
            GalleryPage.SettingsIndex -> holdDown(PRESS)
            GalleryPage.Fields -> {
                onNodeWithTag(FOCUS).requestFocus()
                onAllNodesWithContentDescription("Show password")[1].performClick()
            }
            GalleryPage.Feedback, GalleryPage.Frame, GalleryPage.BottomBar -> {}
        }
    }
}
