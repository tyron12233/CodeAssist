package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = true

// The system back gesture/button, which the IME also consumes to dismiss itself.
actual val hasSystemBack: Boolean = true

// Android shortcuts (and its system Keyboard Shortcuts list) are written with Ctrl.
actual val usesCommandKey: Boolean = false

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun localHourOfDay(): Int = java.time.LocalTime.now().hour
