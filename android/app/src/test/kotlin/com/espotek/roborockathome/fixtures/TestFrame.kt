package com.espotek.roborockathome.fixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.espotek.roborockathome.ui.common.BottomBarState
import com.espotek.roborockathome.ui.common.LocalBottomBar
import com.espotek.roborockathome.ui.common.LocalClock
import com.espotek.roborockathome.ui.common.LocalConnectingToServer
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.Messages
import com.espotek.roborockathome.ui.common.TimeFormats
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahTheme
import java.util.Locale

/**
 * What the app gives a screen, for a screen under test: the theme, the page's background,
 * [messages], a server that is connected, a bottom bar to hide, and [Fixtures.clock] for now and
 * the time zone. A [com.espotek.roborockathome.ui.shell.Shell] inside it provides its own messages,
 * connection and bottom bar.
 */
@Composable
fun TestFrame(dark: Boolean = false, messages: Messages = remember { Messages() }, content: @Composable () -> Unit) {
    RahTheme(dark) {
        CompositionLocalProvider(
            LocalMessages provides messages,
            LocalConnectingToServer provides false,
            LocalClock provides Fixtures.clock,
            LocalBottomBar provides remember { BottomBarState() },
        ) {
            Box(Modifier.fillMaxSize().background(Rah.colors.page)) { content() }
        }
    }
}

/** The words a screen in [TestFrame] has, on a phone in [locale] set to 12 or 24 hours. Needs Robolectric, for Android's ICU. */
fun timeWords(locale: Locale = Locale.US, use24Hour: Boolean = false): TimeWords =
    TimeWords(Fixtures.clock.instant(), Fixtures.clock.zone, TimeFormats(locale, use24Hour))
