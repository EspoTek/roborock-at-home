package com.espotek.roborockathome.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.espotek.roborockathome.R

/**
 * Every icon in the app: one outline family on a 24 dp grid with a 1.8 dp stroke, the web page's set
 * plus what the native screens add. Draw one with [GlyphIcon].
 */
enum class Glyph(@DrawableRes val drawable: Int) {
    Robot(R.drawable.ic_robot),
    Calendar(R.drawable.ic_calendar),
    Activity(R.drawable.ic_activity),
    Settings(R.drawable.ic_settings),
    Play(R.drawable.ic_play),
    Pause(R.drawable.ic_pause),
    Stop(R.drawable.ic_stop),
    Dock(R.drawable.ic_dock),
    Locate(R.drawable.ic_locate),
    Bolt(R.drawable.ic_bolt),
    Alert(R.drawable.ic_alert),
    Pin(R.drawable.ic_pin),
    Recentre(R.drawable.ic_recentre),
    Plus(R.drawable.ic_plus),
    Minus(R.drawable.ic_minus),
    Close(R.drawable.ic_close),
    Copy(R.drawable.ic_copy),
    Trash(R.drawable.ic_trash),
    SignOut(R.drawable.ic_sign_out),
    Wifi(R.drawable.ic_wifi),
    Cloud(R.drawable.ic_cloud),
    Offline(R.drawable.ic_offline),
    Check(R.drawable.ic_check),
    Map(R.drawable.ic_map),
    Back(R.drawable.ic_back),
    ChevronRight(R.drawable.ic_chevron_right),
    Show(R.drawable.ic_show),
    Hide(R.drawable.ic_hide),
    Clock(R.drawable.ic_clock),
}

/**
 * Draws [glyph] in the content colour. [contentDescription] is null when the icon sits beside text
 * that already says the same; an icon-only control should be an IconOnlyButton, which requires one.
 */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = RahSize.icon,
) {
    Icon(painterResource(glyph.drawable), contentDescription, modifier.size(size), tint)
}
