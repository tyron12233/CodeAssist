package dev.ide.platform.log

internal actual fun currentThreadName(): String = Thread.currentThread().name

internal actual fun defaultLogSink(): LogSink? = ConsoleLogSink()

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
