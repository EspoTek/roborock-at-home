package com.espotek.roborockathome.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The designer's colour roles, the same values as the web page's. Read them through [Rah.colors].
 * Room tones are not here: the floor plan owns them, light and dark, with their selected and edge tones.
 */
@Immutable
class RahColors(
    /** Behind everything, including the top bars. */
    val page: Color,
    /** Cards, fields, chips, the bottom bar. */
    val surface: Color,
    val surfaceSecondary: Color,
    val text: Color,
    val textSupporting: Color,
    /** Chevrons, the icons of empty states, and other text and marks that should recede. */
    val textQuiet: Color,
    /** Dividers and the edges of cards: lines that only separate. */
    val outline: Color,
    /**
     * The 1 dp edge of an enabled neutral control a person acts on: fields, outlined buttons,
     * segmented controls, unselected chips. Strong enough (3:1) to find the control by.
     */
    val controlOutline: Color,
    val accent: Color,
    val onAccent: Color,
    /** Behind a selected segment or the selected bottom bar icon. */
    val accentSurface: Color,
    /** Trouble: jams, failures, danger buttons. */
    val attention: Color,
    val attentionSurface: Color,
    val onAttention: Color,
    val mapBackground: Color,
    val mapWall: Color,
    val mapFloor: Color,
    val mapSeen: Color,
)

internal val LightColors = RahColors(
    page = Color(0xFFF5F6F5),
    surface = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFE9EDEB),
    text = Color(0xFF20282C),
    textSupporting = Color(0xFF566269),
    textQuiet = Color(0xFF5C686E),
    outline = Color(0xFFCBD3CF),
    controlOutline = Color(0xFF7B8984),
    accent = Color(0xFF086B5C),
    onAccent = Color(0xFFFFFFFF),
    accentSurface = Color(0xFFDCEFE9),
    attention = Color(0xFFA83D13),
    attentionSurface = Color(0xFFFFF0E7),
    onAttention = Color(0xFFFFFFFF),
    mapBackground = Color(0xFFECEFED),
    mapWall = Color(0xFF677579),
    mapFloor = Color(0xFFD8E0DD),
    mapSeen = Color(0xFFE4EAE7),
)

internal val DarkColors = RahColors(
    page = Color(0xFF141719),
    surface = Color(0xFF20262A),
    surfaceSecondary = Color(0xFF2B3338),
    text = Color(0xFFEEF2F4),
    textSupporting = Color(0xFFB3C0C7),
    textQuiet = Color(0xFF9AA9B1),
    outline = Color(0xFF48565E),
    controlOutline = Color(0xFF7B8A93),
    accent = Color(0xFF64D9C0),
    onAccent = Color(0xFF102B25),
    accentSurface = Color(0xFF203F38),
    attention = Color(0xFFFFAC80),
    attentionSurface = Color(0xFF39271E),
    onAttention = Color(0xFF34180A),
    mapBackground = Color(0xFF1B2024),
    mapWall = Color(0xFF4E5B62),
    mapFloor = Color(0xFF2B353B),
    mapSeen = Color(0xFF232B31),
)

internal val LocalRahColors = staticCompositionLocalOf<RahColors> {
    error("No RahColors here: wrap the content in RahTheme")
}
