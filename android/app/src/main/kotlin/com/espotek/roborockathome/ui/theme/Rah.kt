package com.espotek.roborockathome.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** The design tokens, from any composable inside [RahTheme]: `Rah.colors.accent`, `Rah.type.body`, `Rah.space.s16`. */
object Rah {
    val colors: RahColors
        @Composable @ReadOnlyComposable get() = LocalRahColors.current

    /** Whether the dark theme is showing, for colours the tokens do not cover: the floor plan's tones. */
    val dark: Boolean
        @Composable @ReadOnlyComposable get() = LocalRahColors.current === DarkColors
    val type = RahType
    val space = RahSpace
    val shapes = RahShapes
    val size = RahSize
    val motion = RahMotion
}

/** The only spacing steps. [gutter] is the normal horizontal padding of a screen. */
object RahSpace {
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val gutter = 16.dp
}

object RahShapes {
    /** Chips and the selected segment of a segmented control. */
    val chip = RoundedCornerShape(8.dp)

    /** Buttons, fields, cards, segmented controls and messages. */
    val control = RoundedCornerShape(10.dp)
    val dialog = RoundedCornerShape(20.dp)
}

object RahSize {
    /** The smallest touch target, for every control including pass, day and zoom buttons. */
    val target = 48.dp
    val icon = 24.dp
}

/** Durations in milliseconds. Compose scales them by the system's animation setting, down to none. */
object RahMotion {
    const val PRESS = 120
    const val TRANSITION = 200
    const val RECENTRE = 250
}
