package dev.ide.deps.impl

import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.timeIntervalSince1970

internal actual fun scratchPath(name: String): String = NSTemporaryDirectory() + "deps-impl-" + name

internal actual fun nowSuffix(): String = (NSDate().timeIntervalSince1970 * 1_000_000).toLong().toString()
