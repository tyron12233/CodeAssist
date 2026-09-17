@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.platform.log

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970

internal actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * The thread's own name where it has one, and its role otherwise.
 *
 * A Kotlin/Native worker usually has no name at all, where every JVM thread does, so falling back to
 * "main"/"background" keeps the field meaning what a reader expects rather than being blank half the time.
 */
internal actual fun currentThreadName(): String {
    val thread = NSThread.currentThread
    val named = thread.name
    if (!named.isNullOrBlank()) return named
    return if (thread.isMainThread) "main" else "background"
}

/**
 * `println`, which on iOS reaches the Xcode console and the device log.
 *
 * Unlike the JVM sink there is nothing to capture at construction: nothing here redirects the process's
 * output for the duration of a program run, because running a user's program is not something this platform
 * does.
 */
internal actual fun defaultLogSink(): LogSink? = LogSink { record ->
    val origin = record.source?.let { "$it/" } ?: ""
    println("[${record.level}] $origin${record.tag}: ${record.message}")
    record.throwable?.let { println(it.stackTraceToString()) }
}
