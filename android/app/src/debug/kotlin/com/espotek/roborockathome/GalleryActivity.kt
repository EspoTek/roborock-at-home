package com.espotek.roborockathome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.LocalConnectingToServer
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.SwitchRow
import com.espotek.roborockathome.ui.common.rememberMessages
import com.espotek.roborockathome.ui.gallery.DialogPage
import com.espotek.roborockathome.ui.gallery.GalleryPage
import com.espotek.roborockathome.ui.gallery.GalleryPageFrame
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.RahTheme

/**
 * Debug builds only: every kit component in its states on a real device, the same pages the
 * screenshot tests draw. adb shell am start -n com.espotek.roborockathome/.GalleryActivity
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val deviceDark = isSystemInDarkTheme()
            var dark by rememberSaveable { mutableStateOf(deviceDark) }
            RahTheme(dark) {
                CompositionLocalProvider(LocalMessages provides rememberMessages(), LocalConnectingToServer provides false) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Rah.colors.page)
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SwitchRow("Dark theme", dark, { dark = it })
                        for (page in GalleryPage.entries) {
                            Hairline()
                            GalleryPageFrame(page)
                        }
                        Hairline()
                        DialogPage(startOpen = false)
                    }
                }
            }
        }
    }
}
