package dev.ide.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material 3 Expressive shape scale — rounder and more generous than baseline M3 (whose steps are
 * 4/8/12/16/28 dp). Fed to [androidx.compose.material3.MaterialTheme]; components pick their tier
 * (Buttons → full/large, Cards → medium/large, Sheets/Dialogs → extraLarge) automatically.
 *
 * `small` is deliberately *tighter* than the tier above it rather than following a smooth ramp: the
 * design pairs a large radius against a small one on the same element (see [cardShape]), and that
 * contrast needs the small end to stay small.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
