package dev.ide.ui.platform

/**
 * True on touch-first hosts (Android), false on desktop. Drives platform-differentiated *feel* — chiefly
 * the screen-transition style (mobile shared-axis slide vs. desktop fade+scale) and modal presentation
 * (full-screen cover vs. centered dialog). Resolved per platform via expect/actual, the same mechanism the
 * editor's IME integration uses.
 */
expect val isMobilePlatform: Boolean
