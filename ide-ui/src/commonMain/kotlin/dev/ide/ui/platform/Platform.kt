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
 * Dispatcher for blocking disk I/O (the JVM `Dispatchers.IO` pool on every target). File reads must run here,
 * never on the Compose main thread: on device a tap on a file-tree row that read the file inline stalled the
 * UI thread on FUSE-backed storage and tripped "App not responding". `Dispatchers.IO` isn't visible in
 * `commonMain` (it's a JVM-only declaration), so it's bridged through expect/actual.
 */
expect val ioDispatcher: CoroutineDispatcher
