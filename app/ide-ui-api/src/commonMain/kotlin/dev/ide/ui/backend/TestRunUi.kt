package dev.ide.ui.backend

/** Where one test stands. */
enum class UiTestStatus { Queued, Running, Passed, Failed, Skipped }

/** One test of a run: [className] is the declaring class's FQN, [message] the failure or skip reason. */
data class UiTestCase(
    val className: String,
    val name: String,
    val status: UiTestStatus,
    val durationMs: Long = 0,
    val message: String = "",
)

/**
 * The tests of a run as their results arrive: every test is listed from the start (queued), then moves through
 * running to its outcome. [classFailures] are failures outside any one test (a class's teardown), by class.
 * [finished] once the runner reports the end; [noTests] when it found nothing to run.
 */
data class UiTestRun(
    val cases: List<UiTestCase>,
    val classFailures: Map<String, String> = emptyMap(),
    val finished: Boolean = false,
    val noTests: Boolean = false,
    /** How the run was started, so running its tests again starts the same kind: [TestOutput.LOCAL] (the JVM)
     *  or [TestOutput.INSTRUMENTED] (a device). Announced by the run's first record. */
    val kind: String = TestOutput.LOCAL,
) {
    val passed: Int get() = cases.count { it.status == UiTestStatus.Passed }
    val failed: Int get() = cases.count { it.status == UiTestStatus.Failed } + classFailures.size
    val skipped: Int get() = cases.count { it.status == UiTestStatus.Skipped }
    val durationMs: Long get() = cases.sumOf { it.durationMs }

    /** The cases grouped by class, in run order. */
    val byClass: Map<String, List<UiTestCase>> get() = cases.groupBy { it.className }
}

/** A run console's transcript with the test records taken out: what to show as output, and the tests. */
data class TestConsoleView(val transcript: List<ConsoleChunk>, val tests: UiTestRun?)

/**
 * Splits a run transcript into the program's own output and the records a test run prints between it.
 *
 * The runner (`TestRunnerGenerator` in jvm-build) prints each record as [MARK], the event, `<len>:<class>`,
 * `<len>:<method>`, elapsed milliseconds and `<len>:<message>`, space-separated, ending in a newline. Every variable
 * field is length-prefixed, so names and messages may hold anything, newlines included. The format is spelled in
 * both places because this module cannot depend on the build; a round-trip test in ide-core holds them together.
 *
 * Records stay in the transcript as the build produced it and are split out wherever it is shown. That is what
 * lets a run in the `:build` daemon reach the UI with no new IPC: the transcript already crosses as text. A record
 * cut across two chunks (the producer caps chunk size) is reassembled across consecutive output chunks.
 */
object TestOutput {
    const val MARK = "\u0001ca-test\u0002"

    /** Prefixes a gutter mark's action id that is a test Run task id: tapping it starts that run. */
    const val GUTTER_ACTION_PREFIX = "ca.runTests:"

    /** Run kinds, which are also the task-id prefixes: local tests on the JVM, instrumented tests on a device. */
    const val LOCAL = "test"
    const val INSTRUMENTED = "androidTest"

    /** The record that opens a run and names its kind (its message). Optional: a run without one is [LOCAL]. */
    const val RUN = "RUN"

    /**
     * The Run task id that runs tests of [module]: all of them when [targets] is empty, else each target, a
     * class FQN or `classFqn#method`. Targets are newline-separated, which no class or method name can hold
     * (Kotlin forbids it even between backticks).
     */
    fun taskId(module: String, targets: List<String> = emptyList(), kind: String = LOCAL): String =
        if (targets.isEmpty()) "$kind:$module" else "$kind:$module:" + targets.joinToString("\n")

    /** A [taskId] target for one test. */
    fun target(className: String, method: String? = null): String = if (method == null) className else "$className#$method"

    /** [taskId]'s parts: the module and its targets (empty for every test). Null for a non-test id. */
    fun parseTaskId(id: String): Pair<String, List<String>>? {
        val kind = kindOf(id) ?: return null
        val rest = id.removePrefix("$kind:")
        val module = rest.substringBefore(':')
        val targets = rest.substringAfter(':', "").split('\n').filter { it.isNotEmpty() }
        return module to targets
    }

    /** The kind of test task [id] starts ([LOCAL] or [INSTRUMENTED]), or null for another task. */
    fun kindOf(id: String): String? = when {
        id.startsWith("$LOCAL:") -> LOCAL
        id.startsWith("$INSTRUMENTED:") -> INSTRUMENTED
        else -> null
    }

    /** Runs the same tests as [run] did: one test, one class, or the whole module. */
    fun rerunId(module: String, run: UiTestRun): String {
        val classes = run.cases.map { it.className }.distinct()
        return when {
            run.cases.size == 1 -> taskId(module, listOf(target(run.cases[0].className, run.cases[0].name)), run.kind)
            classes.size == 1 -> taskId(module, classes, run.kind)
            else -> taskId(module, kind = run.kind)
        }
    }

    /** Runs only the tests that failed in [run] (a class whose teardown failed reruns whole). */
    fun rerunFailedId(module: String, run: UiTestRun): String? {
        val targets = run.cases.filter { it.status == UiTestStatus.Failed }.map { target(it.className, it.name) } +
            run.classFailures.keys.map { target(it) }
        return if (targets.isEmpty()) null else taskId(module, targets.distinct(), run.kind)
    }

    fun split(transcript: List<ConsoleChunk>): TestConsoleView {
        if (transcript.none { it.kind == ConsoleChunkKind.OUTPUT && (MARK in it.text || it.text.contains('\u0001')) }) {
            return TestConsoleView(transcript, null)
        }
        val out = ArrayList<ConsoleChunk>(transcript.size)
        val state = State()
        var carry = ""
        for (chunk in transcript) {
            if (chunk.kind != ConsoleChunkKind.OUTPUT) {
                if (carry.isNotEmpty()) { out.add(ConsoleChunk(carry, ConsoleChunkKind.OUTPUT)); carry = "" }
                out.add(chunk)
                continue
            }
            val text = carry + chunk.text
            carry = ""
            val kept = StringBuilder()
            var i = 0
            while (i < text.length) {
                val at = text.indexOf(MARK, i)
                if (at < 0) {
                    // A partial MARK at the very end may complete in the next chunk.
                    val tail = partialMarkSuffix(text, i)
                    kept.append(text, i, text.length - tail)
                    carry = text.substring(text.length - tail)
                    break
                }
                kept.append(text, i, at)
                val end = parse(text, at + MARK.length, state)
                if (end < 0) { carry = text.substring(at); break } // incomplete: wait for the rest
                i = end
            }
            if (kept.isNotEmpty()) out.add(ConsoleChunk(kept.toString(), ConsoleChunkKind.OUTPUT))
        }
        if (carry.isNotEmpty() && MARK !in carry) out.add(ConsoleChunk(carry, ConsoleChunkKind.OUTPUT))
        return TestConsoleView(mergeAdjacent(out), state.run())
    }

    /** Parses one record body starting at [from]; returns the index past it, or -1 when it is incomplete. */
    private fun parse(text: String, from: Int, state: State): Int {
        var p = from
        fun word(): String? {
            val e = text.indexOf(' ', p)
            if (e < 0) return null
            return text.substring(p, e).also { p = e + 1 }
        }
        fun field(): String? {
            val colon = text.indexOf(':', p)
            if (colon < 0) return null
            val len = text.substring(p, colon).toIntOrNull() ?: return null
            val start = colon + 1
            if (start + len >= text.length) return null // the value and its separator must both be present
            return text.substring(start, start + len).also { p = start + len + 1 }
        }
        val event = word() ?: return -1
        val cls = field() ?: return -1
        val method = field() ?: return -1
        val ms = word()?.toLongOrNull() ?: return -1
        val message = field() ?: return -1
        state.apply(event, cls, method, ms, message)
        return p
    }

    /** How many trailing characters of [text] (from [from]) are a proper prefix of [MARK]. */
    private fun partialMarkSuffix(text: String, from: Int): Int {
        for (n in minOf(MARK.length - 1, text.length - from) downTo 1) {
            if (text.endsWith(MARK.substring(0, n))) return n
        }
        return 0
    }

    private fun mergeAdjacent(chunks: List<ConsoleChunk>): List<ConsoleChunk> {
        val out = ArrayList<ConsoleChunk>(chunks.size)
        for (c in chunks) {
            val last = out.lastOrNull()
            if (last != null && last.kind == c.kind) out[out.size - 1] = ConsoleChunk(last.text + c.text, c.kind) else out.add(c)
        }
        return out
    }

    private class State {
        val cases = LinkedHashMap<String, UiTestCase>()
        val classFailures = LinkedHashMap<String, String>()
        var finished = false
        var noTests = false
        var any = false
        var kind = LOCAL

        fun apply(event: String, cls: String, method: String, ms: Long, message: String) {
            any = true
            val key = "$cls#$method"
            fun set(status: UiTestStatus) {
                cases[key] = UiTestCase(cls, method, status, ms, message)
            }
            when (event) {
                "QUEUED" -> if (key !in cases) set(UiTestStatus.Queued)
                "STARTED" -> set(UiTestStatus.Running)
                "PASSED" -> set(UiTestStatus.Passed)
                "FAILED" -> set(UiTestStatus.Failed)
                "SKIPPED" -> set(UiTestStatus.Skipped)
                "CLASS_FAILED" -> classFailures[cls] = message
                "FINISHED" -> { finished = true; noTests = message == "no tests" && cases.isEmpty() }
                RUN -> kind = message.ifEmpty { LOCAL }
            }
        }

        fun run(): UiTestRun? = if (!any) null else UiTestRun(cases.values.toList(), classFailures, finished, noTests, kind)
    }
}

/**
 * The latest outcome of each test this session, so the editor's gutter can show it beside the test: a test's
 * own result, and for a class the worst of its tests' (one failure fails the class). Fed from every run's
 * results ([record]); a later run of a test replaces what an earlier one said. Keyed by module, class and
 * method, so a run of one test leaves the rest of its class as they were.
 */
object TestResultMemory {
    private var byTest: Map<String, UiTestStatus> = emptyMap()
    private var recorded: Pair<Int, Int>? = null

    /** Fold the results of console run [runId] (its [tests], from module [module]) in. Cheap to call on every
     *  update of the same run: an unchanged run is skipped. Returns the current memory. */
    fun record(runId: Int, module: String, tests: UiTestRun?): Map<String, UiTestStatus> {
        if (tests == null) return byTest
        val stamp = runId to tests.cases.sumOf { it.status.ordinal + 1 } + tests.classFailures.size * 31
        if (stamp == recorded) return byTest
        recorded = stamp
        val next = HashMap(byTest)
        for (c in tests.cases) {
            when (c.status) {
                UiTestStatus.Passed, UiTestStatus.Failed, UiTestStatus.Skipped -> next[key(module, c.className, c.name)] = c.status
                else -> {}
            }
        }
        for (cls in tests.classFailures.keys) next[key(module, cls, null)] = UiTestStatus.Failed
        byTest = next
        return next
    }

    /** What is known of the test or class a [TestOutput.taskId] with a single target names, or null. */
    fun statusOf(memory: Map<String, UiTestStatus>, taskId: String): UiTestStatus? {
        val (module, targets) = TestOutput.parseTaskId(taskId) ?: return null
        val target = targets.singleOrNull() ?: return null
        if ('#' in target) {
            val cls = target.substringBefore('#')
            val method = target.substringAfter('#')
            // A parameterized test's invocations are recorded as `name[1] …`; the test is as bad as its worst.
            val own = memory[key(module, cls, method)]
            val invocations = memory.filterKeys { it.startsWith(key(module, cls, "$method[")) }.values
            return worst(listOfNotNull(own) + invocations)
        }
        val prefix = key(module, target, "")
        return worst(memory.filterKeys { it.startsWith(prefix) || it == key(module, target, null) }.values)
    }

    private fun worst(statuses: Collection<UiTestStatus>): UiTestStatus? = when {
        statuses.isEmpty() -> null
        UiTestStatus.Failed in statuses -> UiTestStatus.Failed
        UiTestStatus.Passed in statuses -> UiTestStatus.Passed
        else -> UiTestStatus.Skipped
    }

    private fun key(module: String, cls: String, method: String?) = "$module|$cls#${method ?: ""}"
}
