package dev.ide.store.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val storeIo: CoroutineDispatcher = Dispatchers.IO
