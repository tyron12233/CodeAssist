package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * True on touch-first hosts (Android), false on desktop. Drives platform-differentiated *feel* — chiefly
 * the screen-transition style (mobile shared-axis slide vs. desktop fade+scale) and modal presentation
 * (full-screen cover vs. centered dialog). Resolved per platform via expect/actual, the same mechanism the
 * editor's IME integration uses.
 */
expect val isMobilePlatform: Boolean

/**
 * Whether the host gives the user a system-level "back" — the Android gesture/button.
 *
 * It exists because of what back does for free there and nowhere else: **the Android IME consumes back to
 * dismiss itself**, so an app needs no key of its own to put the soft keyboard away. iOS has no back at all
 * (see [PlatformBackHandler]'s iOS actual), so the editor's accessory bar has to offer the way out, or a
 * keyboard raised by a tap can never be dismissed. Desktop has no soft keyboard to dismiss.
 */
expect val hasSystemBack: Boolean

/**
 * Dispatcher for blocking disk I/O (the JVM `Dispatchers.IO` pool on every target). File reads must run here,
 * never on the Compose main thread: on device a tap on a file-tree row that read the file inline stalled the
 * UI thread on FUSE-backed storage and tripped "App not responding". `Dispatchers.IO` isn't visible in
 * `commonMain` (it's a JVM-only declaration), so it's bridged through expect/actual.
 */
expect val ioDispatcher: CoroutineDispatcher

/**
 * Wall-clock time in epoch-ms. Used for relative-time labels (e.g. the project picker's "opened 2h ago").
 * `System.currentTimeMillis()` isn't visible in `commonMain`, so it's bridged through expect/actual.
 */
expect fun nowMillis(): Long

/**
 * The local hour of day, 0..23. Drives the Home screen's time-of-day greeting.
 *
 * Separate from [nowMillis] because turning an epoch into a *local* hour needs the platform's time zone,
 * and that is exactly the part `commonMain` cannot see.
 */
expect fun localHourOfDay(): Int
