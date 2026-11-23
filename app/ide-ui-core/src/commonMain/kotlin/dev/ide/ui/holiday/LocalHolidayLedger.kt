package dev.ide.ui.holiday

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's [HolidayLedger], provided once at the root, so a screen where something worth a star happens
 * (a challenge solved, a lesson passed, a project sent to the Store) can record it without being handed the
 * ledger. Null in a snapshot or preview with no root, where nothing is recorded.
 */
val LocalHolidayLedger = staticCompositionLocalOf<HolidayLedger?> { null }
