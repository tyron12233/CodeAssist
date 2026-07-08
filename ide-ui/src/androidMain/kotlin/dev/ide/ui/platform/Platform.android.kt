package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = true

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
