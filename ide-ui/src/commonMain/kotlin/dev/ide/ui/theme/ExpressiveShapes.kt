package dev.ide.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material 3 Expressive shape scale — rounder and more generous than baseline M3 (whose steps are
 * 4/8/12/16/28 dp). Expressive favors a friendlier, pill-leaning silhouette, so every tier bumps up. Fed to
 * [androidx.compose.material3.MaterialTheme]; components pick their tier (Buttons → full/large, Cards →
 * medium/large, Sheets/Dialogs → extraLarge) automatically.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
