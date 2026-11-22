package com.espotek.roborockathome.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Whether the app is waiting for the server. The shell provides it; [ScreenFrame] shows the strip for it. */
val LocalConnectingToServer = staticCompositionLocalOf<Boolean> {
    error("No connection state here: the shell provides it")
}

/**
 * Every screen's frame: [topBar] below the status bar and any cutout, then the [ConnectionStrip]
 * while the app is waiting for the server, then [content] in the rest of the height, kept above the
 * keyboard. The shell has already kept it clear of the bottom bar and the side insets, so [content]
 * needs no insets of its own; a scrolling body brings a focused field into view by itself.
 */
@Composable
fun ScreenFrame(topBar: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))) {
            topBar()
        }
        if (LocalConnectingToServer.current) ConnectionStrip()
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            content()
        }
    }
}
