// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.log

import dev.ide.platform.epochMillis

import dev.ide.platform.Lock

/** Severity of a [LogRecord], ordered DEBUG < INFO < WARN < ERROR (compare by [ordinal]). */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One logged event. [throwable] is the cause when logging a caught exception. By convention an
 * `ERROR` with a non-null [throwable] is an **unexpected** failure — that's what surfaces the
 * IntelliJ-style critical-error dialog; routine/handled failures should log at `WARN`.
 *
 * [source] attributes the record to whoever emitted it: the id of the plugin whose [Logger] produced it
 * (set by the plugin registrar), or null for IDE/core logs. The Logs viewer uses it to filter by plugin.
 */
data class LogRecord(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
    val timestampMs: Long,
    val threadName: String,
    val source: String? = null,
)

/** A destination for log records (console, an in-memory ring, the crash/analytics bridge, …). */
fun interface LogSink {
    fun log(record: LogRecord)
}

/**
 * A tag-bound entry point for logging. Obtain one via [Log.logger]; cheap to hold as a `val`. All
 * methods are non-throwing — logging must never be able to break the caller.
 *
 * [source] is the emitting plugin's id (null for IDE/core loggers); it is stamped onto every [LogRecord]
 * this logger produces so the Logs viewer can attribute and filter by plugin. It is set by the platform
 * when a plugin mints its logger, never by the caller, so it cannot be spoofed.
 */
class Logger internal constructor(private val tag: String, private val source: String? = null) {
    fun debug(message: String) = Log.dispatch(LogLevel.DEBUG, tag, message, null, source)
    fun info(message: String) = Log.dispatch(LogLevel.INFO, tag, message, null, source)
    fun warn(message: String, throwable: Throwable? = null) = Log.dispatch(LogLevel.WARN, tag, message, throwable, source)
    /** An unexpected failure. With a [throwable] this also surfaces the host's critical-error dialog. */
    fun error(message: String, throwable: Throwable? = null) = Log.dispatch(LogLevel.ERROR, tag, message, throwable, source)
}

/**
 * The process-wide logging hub. Records fan out to every registered [LogSink]; a [RingBufferSink] (kept
 * by default) retains the most recent records so reports/diagnostics can attach recent context via
 * [recent]. A console sink is also registered by default. Hosts add their own sinks (the critical-error
 * dialog bridge, the analytics bridge) at startup.
 *
 * Deliberately dependency-free and platform-neutral (it is in the lowest module, depended on by everything),
 * so any module can `Log.logger("tag")` without a back-dependency. Thread-safe; a misbehaving sink can't
 * break dispatch (each call is guarded).
 *
 * Three things here are not platform-neutral, and each is a seam rather than a fork of the whole hub: the
 * clock, the current thread's name, and what a default console sink is. On the JVM the last of those binds
 * the console streams at CONSTRUCTION, which is load-bearing (see `ConsoleLogSink`), and that is exactly the
 * sort of detail a common implementation would have quietly lost.
 */
object Log {
    private val lock = Lock()
    private var sinks: List<LogSink> = emptyList()

    /** The default in-memory ring — recent records for the in-app Logs viewer / attaching to a report. */
    val ring = RingBufferSink(capacity = 1000)

    /** Records below this level are dropped before reaching any sink. */
    var minLevel: LogLevel = LogLevel.DEBUG

    init {
        defaultLogSink()?.let { sinks = sinks + it }
        sinks = sinks + ring
    }

    fun logger(tag: String): Logger = Logger(tag)

    /** A logger attributed to [source] (a plugin id) — its records carry it so the Logs viewer can filter
     *  by plugin. The platform sets [source] when a plugin mints its logger; callers cannot forge it. */
    fun logger(tag: String, source: String?): Logger = Logger(tag, source)

    fun addSink(sink: LogSink) {
        lock.withLock { sinks = sinks + sink }
    }

    fun removeSink(sink: LogSink) {
        lock.withLock { sinks = sinks - sink }
    }

    /** A snapshot of the most recent records (oldest first), for diagnostics or a report attachment. */
    fun recent(): List<LogRecord> = ring.snapshot()

    internal fun dispatch(level: LogLevel, tag: String, message: String, throwable: Throwable?, source: String? = null) {
        if (level.ordinal < minLevel.ordinal) return
        val record = LogRecord(level, tag, message, throwable, epochMillis(), currentThreadName(), source)
        // Read once: the list is replaced, never mutated, so a dispatch cannot see a half-updated set of
        // sinks and does not have to hold the lock while a sink runs.
        for (sink in sinks) runCatching { sink.log(record) }
    }
}

/** Keeps the last [capacity] records in memory (drops the oldest). Thread-safe. */
class RingBufferSink(private val capacity: Int) : LogSink {
    private val lock = Lock()
    private val buffer = ArrayDeque<LogRecord>(capacity)

    override fun log(record: LogRecord) {
        lock.withLock {
            buffer.addLast(record)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    fun snapshot(): List<LogRecord> = lock.withLock { buffer.toList() }
}

/** The running thread's name, for attributing a record to the work that produced it. */
internal expect fun currentThreadName(): String

/** The console sink this platform starts with, or null where there is nothing sensible to print to. */
internal expect fun defaultLogSink(): LogSink?
