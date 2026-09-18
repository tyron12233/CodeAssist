package dev.ide.lang.kotlin

import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.timeIntervalSince1970

internal actual fun scratchPath(name: String): String = NSTemporaryDirectory() + "lang-kotlin-" + name

internal actual fun nowSuffix(): String = (NSDate().timeIntervalSince1970 * 1_000_000).toLong().toString()
