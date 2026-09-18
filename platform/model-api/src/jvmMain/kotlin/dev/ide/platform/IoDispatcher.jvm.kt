package dev.ide.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The JVM's own pool, which grows as its threads block. ART has the same one. */
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
