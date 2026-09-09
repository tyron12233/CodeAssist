package dev.ide.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Material 3 Expressive type scale, bound to the app's UI face.
 *
 * Two departures from stock M3 do most of the work of making the UI not read as a default Material app:
 *
 * - **Negative tracking on the large sizes.** Display and headline roles tighten to between -0.4 and
 *   -1 sp. At 34 sp, default tracking looks slack; pulling it in is what makes a screen title read as a
 *   set piece rather than as large body text.
 * - **A light display weight.** `displaySmall` is [FontWeight.Light], not bold. Screen titles ("Your
 *   projects", "Explore", "Learn") get their presence from size and tracking, and the weight contrast
 *   against the medium-weight titles below them is the point.
 *
 * `labelSmall` is deliberately **bold with wide tracking**: it is the all-caps eyebrow label ("ABOUT",
 * "SORT BY", "CONTINUE"), which needs the extra letter spacing to stay legible in caps at 12 sp.
 *
 * Fed to [androidx.compose.material3.MaterialTheme], so every native M3 component picks this up. Sizes
 * are in sp and therefore scale with the user's font scale; nothing in the redesign fixes a text
 * container's height, so a 2.0 scale wraps rather than clips.
 */
fun expressiveTypography(ui: FontFamily): Typography = Typography(
    displayLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Light, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Light, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-1.2).sp),
    displaySmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Light, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    // The all-caps eyebrow. Bold + wide tracking, because caps at 12 sp need the separation.
    labelSmall = TextStyle(fontFamily = ui, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.7.sp),
)
