package com.espotek.roborockathome.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * The app's look, light or dark. Inside it, [Rah] gives the tokens and stock Material 3 components
 * take the same colours, type and shapes. It also draws the activity edge to edge with system bar
 * icons that suit [dark]. It does not paint a background: the shell does. A plain Modifier.clickable
 * inside it gets the kit's press wash over its whole bounds, so clip the node to its shape first.
 */
@Composable
fun RahTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    // Android Studio's previews have no activity and no system bars.
    if (!view.isInEditMode) {
        val activity = LocalContext.current.componentActivity()
        DisposableEffect(activity, dark) {
            val style = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }
    MaterialTheme(
        colorScheme = materialColors(colors, inverse = if (dark) LightColors else DarkColors),
        typography = MaterialTypography,
        shapes = MaterialShapes,
    ) {
        CompositionLocalProvider(
            LocalRahColors provides colors,
            LocalContentColor provides colors.text,
            LocalTextStyle provides RahType.body,
            LocalIndication provides PressIndication,
            LocalTonalElevationEnabled provides false,
            content = content,
        )
    }
}

private tailrec fun Context.componentActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> error("RahTheme has to be inside a ComponentActivity, and $this is not one")
}

// Every role is set, so nothing falls back to Material's own purple. The surface containers are the
// plain surface: separation comes from outlines, not tints. The highest one is the secondary surface,
// because the time picker draws its dial in it on a dialog of the plain surface.
private fun materialColors(c: RahColors, inverse: RahColors) = ColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSurface,
    onPrimaryContainer = c.text,
    inversePrimary = inverse.accent,
    secondary = c.accent,
    onSecondary = c.onAccent,
    secondaryContainer = c.accentSurface,
    onSecondaryContainer = c.text,
    tertiary = c.accent,
    onTertiary = c.onAccent,
    tertiaryContainer = c.accentSurface,
    onTertiaryContainer = c.text,
    background = c.page,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceSecondary,
    onSurfaceVariant = c.textSupporting,
    surfaceTint = c.surface,
    inverseSurface = c.text,
    inverseOnSurface = c.page,
    error = c.attention,
    onError = c.onAttention,
    errorContainer = c.attentionSurface,
    onErrorContainer = c.text,
    // Material draws control edges in outline and decorative lines in outlineVariant.
    outline = c.controlOutline,
    outlineVariant = c.outline,
    scrim = Color.Black,
    surfaceBright = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surfaceSecondary,
    surfaceContainerLow = c.surface,
    surfaceContainerLowest = c.surface,
    surfaceDim = c.surfaceSecondary,
)

// Display and large headline styles stay Material's: only the time and date pickers use them, for
// their big digits.
private val MaterialTypography = Typography().copy(
    headlineSmall = RahType.pageTitle,
    titleLarge = RahType.status,
    titleMedium = RahType.sectionHeading,
    titleSmall = RahType.label,
    bodyLarge = RahType.body,
    bodyMedium = RahType.supporting,
    bodySmall = RahType.supporting,
    labelLarge = RahType.button,
    labelMedium = RahType.label,
    labelSmall = RahType.navigation,
)

private val MaterialShapes = Shapes(
    extraSmall = RahShapes.control,
    small = RahShapes.chip,
    medium = RahShapes.control,
    large = RahShapes.dialog,
    extraLarge = RahShapes.dialog,
)
