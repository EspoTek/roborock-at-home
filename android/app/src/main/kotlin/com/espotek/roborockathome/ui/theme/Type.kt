package com.espotek.roborockathome.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The designer's type scale in the system sans-serif. Sizes are in sp, so they follow the system font size. */
object RahType {
    val pageTitle = style(26.sp, 32.sp, FontWeight.SemiBold)

    /** Robot status and progress values. Numerals are tabular. */
    val status = style(20.sp, 26.sp, FontWeight.SemiBold).tabular()
    val sectionHeading = style(16.sp, 24.sp, FontWeight.SemiBold)

    /** Body text and what people type into fields. */
    val body = style(16.sp, 24.sp, FontWeight.Normal)
    val button = style(16.sp, 20.sp, FontWeight.Medium)

    /** Chips, segments, field labels and other compact labels. */
    val label = style(14.sp, 20.sp, FontWeight.Medium)
    val supporting = style(14.sp, 20.sp, FontWeight.Normal)
    val navigation = style(12.sp, 16.sp, FontWeight.Medium)

    private fun style(size: TextUnit, lineHeight: TextUnit, weight: FontWeight) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = size,
        lineHeight = lineHeight,
        fontWeight = weight,
        // Text sits in the middle of its line, so a label is centred in its button at any size.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

/** The same style with tabular numerals, for times, battery and progress, so digits do not shift as they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
