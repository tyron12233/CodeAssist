package dev.ide.core.services

import dev.ide.build.jvm.test.TestProtocol

/**
 * Reads what `am instrument -r -w` prints and reports each test as a [TestProtocol] record through [emit], the
 * same records a local test run prints, so the Run screen shows an instrumented run like any other.
 *
 * The raw format is a sequence of status bundles, each a run of `INSTRUMENTATION_STATUS: key=value` lines closed
 * by `INSTRUMENTATION_STATUS_CODE: n`, then one result bundle (`INSTRUMENTATION_RESULT: key=value`, closed by
 * `INSTRUMENTATION_CODE: n`). A value continues over the following lines until the next `INSTRUMENTATION_` line
 * (a stack trace is many lines). Status codes: 1 a test started, 0 it passed, -1 an error, -2 a failure,
 * -3 ignored, -4 an assumption that did not hold.
 */
class InstrumentationResults(
    private val emit: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val status = LinkedHashMap<String, StringBuilder>()
    private val result = LinkedHashMap<String, StringBuilder>()
    private var current: StringBuilder? = null
    private val startedAt = HashMap<String, Long>()
    private val running = LinkedHashSet<Pair<String, String>>()

    /** How many tests failed so far. */
    var failed = 0
        private set

    /** How many tests reported an outcome. */
    var finished = 0
        private set

    /** One line of output, in order, including partial values' continuation lines. */
    @Synchronized
    fun line(raw: String) {
        val line = raw.trimEnd('\r')
        when {
            line.startsWith(STATUS) -> current = put(status, line.removePrefix(STATUS))
            line.startsWith(STATUS_CODE) -> {
                line.removePrefix(STATUS_CODE).trim().toIntOrNull()?.let(::onStatus)
                status.clear(); current = null
            }
            line.startsWith(RESULT) -> current = put(result, line.removePrefix(RESULT))
            line.startsWith(CODE) -> current = null
            line.startsWith(FAILED_PREFIX) -> result.getOrPut("failed") { StringBuilder() }.append(line.removePrefix(FAILED_PREFIX))
            else -> current?.append('\n')?.append(line)
        }
    }

    /**
     * The run is over: a test that started and never reported (the app's process crashed under it) fails with
     * the reason the instrumentation gave, and the run's end is recorded. Returns what the console should say
     * beside the results, if anything (a crash, a missing runner).
     */
    @Synchronized
    fun finish(): String? {
        val crash = listOfNotNull(result["shortMsg"]?.toString(), result["longMsg"]?.toString(), result["failed"]?.toString())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(": ")
        for ((cls, test) in running.toList()) {
            emit(TestProtocol.record(TestProtocol.FAILED, cls, test, elapsed(cls, test), crash.ifEmpty { "The test did not finish" }))
            failed++; finished++
        }
        running.clear()
        emit(TestProtocol.record(TestProtocol.FINISHED, message = if (finished == 0 && crash.isEmpty()) "no tests" else ""))
        return crash.ifEmpty { null }
    }

    private fun onStatus(code: Int) {
        val cls = status["class"]?.toString()?.trim() ?: return
        val test = status["test"]?.toString()?.trim() ?: return
        val key = cls to test
        when (code) {
            1 -> {
                startedAt["$cls#$test"] = clock()
                running += key
                emit(TestProtocol.record(TestProtocol.STARTED, cls, test))
            }
            0 -> done(key, TestProtocol.PASSED, "")
            -1, -2 -> { failed++; done(key, TestProtocol.FAILED, stack()) }
            -3 -> done(key, TestProtocol.SKIPPED, "ignored")
            -4 -> done(key, TestProtocol.SKIPPED, stack().lineSequence().firstOrNull().orEmpty())
        }
    }

    private fun done(key: Pair<String, String>, event: String, message: String) {
        running -= key
        finished++
        emit(TestProtocol.record(event, key.first, key.second, elapsed(key.first, key.second), message))
    }

    /** The failure's stack, kept to its head: the frames under the test runner's own add nothing to read. */
    private fun stack(): String = status["stack"]?.toString()?.trim()?.lineSequence()?.take(STACK_LINES)?.joinToString("\n").orEmpty()

    private fun elapsed(cls: String, test: String): Long = startedAt["$cls#$test"]?.let { clock() - it } ?: 0

    /** `key=value` into [bundle], returning the value's builder so continuation lines append to it. */
    private fun put(bundle: MutableMap<String, StringBuilder>, entry: String): StringBuilder {
        val eq = entry.indexOf('=')
        val key = if (eq < 0) entry.trim() else entry.substring(0, eq).trim()
        val value = if (eq < 0) "" else entry.substring(eq + 1)
        return StringBuilder(value).also { bundle[key] = it }
    }

    private companion object {
        const val STATUS = "INSTRUMENTATION_STATUS: "
        const val STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
        const val RESULT = "INSTRUMENTATION_RESULT: "
        const val CODE = "INSTRUMENTATION_CODE: "
        const val FAILED_PREFIX = "INSTRUMENTATION_FAILED: "
        const val STACK_LINES = 40
    }
}
