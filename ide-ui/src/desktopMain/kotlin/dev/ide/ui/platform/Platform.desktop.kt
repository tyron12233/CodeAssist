package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = false

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun nowMillis(): Long = System.currentTimeMillis()
