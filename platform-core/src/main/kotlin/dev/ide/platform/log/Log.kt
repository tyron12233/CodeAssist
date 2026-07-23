package dev.ide.platform.log

import java.util.concurrent.CopyOnWriteArrayList

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
 * [recent]. A [ConsoleLogSink] is also registered by default. Hosts add their own sinks (the
 * critical-error dialog bridge, the analytics bridge) at startup.
 *
 * Deliberately dependency-free and platform-neutral (lives in platform-core, depended on by everything),
 * so any module can `Log.logger("tag")` without a back-dependency. Thread-safe; a misbehaving sink can't
 * break dispatch (each call is guarded).
 */
object Log {
    private val sinks = CopyOnWriteArrayList<LogSink>()

    /** The default in-memory ring — recent records for the in-app Logs viewer / attaching to a report. */
    val ring = RingBufferSink(capacity = 1000)

    /** Records below this level are dropped before reaching any sink. */
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    init {
        sinks.add(ConsoleLogSink())
        sinks.add(ring)
    }

    fun logger(tag: String): Logger = Logger(tag)

    /** A logger attributed to [source] (a plugin id) — its records carry it so the Logs viewer can filter
     *  by plugin. The platform sets [source] when a plugin mints its logger; callers cannot forge it. */
    fun logger(tag: String, source: String?): Logger = Logger(tag, source)

    fun addSink(sink: LogSink) { sinks.add(sink) }
    fun removeSink(sink: LogSink) { sinks.remove(sink) }

    /** A snapshot of the most recent records (oldest first), for diagnostics or a report attachment. */
    fun recent(): List<LogRecord> = ring.snapshot()

    internal fun dispatch(level: LogLevel, tag: String, message: String, throwable: Throwable?, source: String? = null) {
        if (level.ordinal < minLevel.ordinal) return
        val record = LogRecord(level, tag, message, throwable, System.currentTimeMillis(), Thread.currentThread().name, source)
        for (sink in sinks) runCatching { sink.log(record) }
    }
}

/** Keeps the last [capacity] records in memory (drops the oldest). Thread-safe. */
class RingBufferSink(private val capacity: Int) : LogSink {
    private val lock = Any()
    private val buffer = ArrayDeque<LogRecord>(capacity)

    override fun log(record: LogRecord) {
        synchronized(lock) {
            buffer.addLast(record)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    fun snapshot(): List<LogRecord> = synchronized(lock) { buffer.toList() }
}

/**
 * Prints records to stdout (and stack traces to stderr for ERROR). The baseline sink for desktop/logcat.
 *
 * Binds to the console streams captured at CONSTRUCTION — not at each call. [Log] builds the default sink at
 * process startup, long before any program run, so this captures the true console (the desktop terminal /
 * the Android `System.out`→logcat redirect). That matters because a program run redirects the process-global
 * `System.out`/`System.err`/`System.in` to the run console for the duration of the run (so the interpreted
 * program's output and bridged standard-library I/O both reach it); a call-time `println` would then dump
 * every concurrent IDE log (build tasks, the `ide.mem` heartbeat, daemon chatter) into the user program's
 * output. Holding the originals keeps IDE logs out of it.
 */
class ConsoleLogSink(
    private val out: java.io.PrintStream = System.out,
    private val err: java.io.PrintStream = System.err,
) : LogSink {
    override fun log(record: LogRecord) {
        val origin = record.source?.let { "$it/" } ?: ""
        val line = "[${record.level}] $origin${record.tag}: ${record.message}"
        if (record.level == LogLevel.ERROR) {
            err.println(line)
            record.throwable?.printStackTrace(err)
        } else {
            out.println(line)
            // A WARN with an attached throwable used to swallow it entirely, leaving bare "X failed" lines
            // in the device log with no cause. Print the stack for those too.
            record.throwable?.printStackTrace(out)
        }
    }
}
