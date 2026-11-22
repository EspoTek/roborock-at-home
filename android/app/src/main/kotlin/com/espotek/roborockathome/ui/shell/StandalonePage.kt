package com.espotek.roborockathome.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.ui.theme.Rah

/**
 * A page outside the shell, such as sign-in: one narrow column, centred in the window while it fits
 * and scrolling when it does not, always above the keyboard.
 */
@Composable
fun StandalonePage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Rah.colors.page)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                // The web page's sign-in keeps to the same narrow column on a wide window.
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(horizontal = Rah.space.gutter, vertical = Rah.space.s32),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}
