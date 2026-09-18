package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = true

// The system back gesture/button, which the IME also consumes to dismiss itself.
actual val hasSystemBack: Boolean = true

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun localHourOfDay(): Int = java.time.LocalTime.now().hour
