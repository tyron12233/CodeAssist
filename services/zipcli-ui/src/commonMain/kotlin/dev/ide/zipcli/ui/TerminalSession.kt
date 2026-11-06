package dev.ide.zipcli.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one terminal line is, for coloring. */
enum class TerminalLineKind { INPUT, OUTPUT, ERROR, INFO }

data class TerminalLine(val text: String, val kind: TerminalLineKind)

/** How a run ended; the output itself was streamed line by line while it ran. */
data class TerminalRunResult(val status: Int, val cwd: String, val timedOut: Boolean)

/**
 * The engine a [TerminalSession] drives. An interface so the Compose code here stays platform-neutral: the
 * implementation (over ZipCLI's JVM-only core) lives in the `jvmShared` source set.
 */
interface TerminalEngine {
    /** The working directory before anything has run. */
    val initialCwd: String

    /**
     * Runs one line (or script). [onLine] receives every output line as it is printed; [confirm] answers a
     * confirmation prompt (a destructive step) and suspends until the user has.
     */
    suspend fun run(script: String, onLine: (String) -> Unit, confirm: suspend (String) -> Boolean): TerminalRunResult
}

/** A confirmation the running command is waiting on. */
class ConfirmPrompt internal constructor(val text: String, internal val answer: CompletableDeferred<Boolean>)

/**
 * One terminal's state: the scrollback, the working directory, the command history, and the command that is
 * running. It owns its [scope], not the composition's, so a command keeps running (and its output keeps
 * arriving) while the tool window is on another tab, and the scrollback is still there when it comes back.
 * Sessions are kept per project by the plugin.
 */
class TerminalSession(
    private val engine: TerminalEngine,
    private val scope: CoroutineScope,
) {
    /** Called after every run: it may have created, changed or removed files (the plugin refreshes the tree). */
    var onFilesMayHaveChanged: () -> Unit = {}

    private val _lines = MutableStateFlow(
        listOf(TerminalLine("ZipCLI. Type help to list commands; the project root is /project.", TerminalLineKind.INFO)),
    )
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _cwd = MutableStateFlow(engine.initialCwd)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _prompt = MutableStateFlow<ConfirmPrompt?>(null)
    val prompt: StateFlow<ConfirmPrompt?> = _prompt.asStateFlow()

    private val history = ArrayList<String>()
    private var cursor = 0
    private var job: Job? = null

    /** Runs [text]. Ignored while another command is still running or when it is blank. */
    fun submit(text: String) {
        val command = text.trim()
        if (command.isEmpty() || _busy.value) return
        if (history.lastOrNull() != command) history += command
        if (history.size > MAX_HISTORY) history.removeAt(0)
        cursor = history.size
        append(TerminalLine("${_cwd.value} $ $command", TerminalLineKind.INPUT))
        if (command == "clear") { clear(); return }

        _busy.value = true
        job = scope.launch {
            try {
                val result = engine.run(command, onLine = ::appendOutput, confirm = ::askConfirmation)
                _cwd.value = result.cwd
                if (result.status != 0 && !result.timedOut) append(TerminalLine("[exit ${result.status}]", TerminalLineKind.INFO))
            } catch (e: CancellationException) {
                append(TerminalLine("^C", TerminalLineKind.INFO))
                throw e
            } catch (t: Throwable) {
                append(TerminalLine("error: ${t.message ?: t::class.simpleName}", TerminalLineKind.ERROR))
            } finally {
                _prompt.value?.answer?.complete(false)
                _prompt.value = null
                _busy.value = false
                onFilesMayHaveChanged()
            }
        }
    }

    /** Answers the pending confirmation; the command carries on (yes) or skips that step (no). */
    fun answer(yes: Boolean) {
        _prompt.value?.answer?.complete(yes)
    }

    /** Cancels the running command. A step already in the middle of a file operation finishes first. */
    fun stop() {
        job?.cancel()
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** The previous history entry for the Up key, or null when there is none. */
    fun previous(): String? {
        if (history.isEmpty() || cursor <= 0) return null
        cursor -= 1
        return history[cursor]
    }

    /** The next history entry for the Down key; an empty string past the newest, null when not navigating. */
    fun next(): String? {
        if (cursor >= history.size) return null
        cursor += 1
        return if (cursor == history.size) "" else history[cursor]
    }

    private suspend fun askConfirmation(text: String): Boolean {
        val prompt = ConfirmPrompt(text, CompletableDeferred())
        _prompt.value = prompt
        try {
            return prompt.answer.await()
        } finally {
            _prompt.value = null
        }
    }

    private fun appendOutput(text: String) {
        val kind = if (text.startsWith("error: ")) TerminalLineKind.ERROR else TerminalLineKind.OUTPUT
        append(TerminalLine(text, kind))
    }

    private fun append(line: TerminalLine) {
        _lines.update { old ->
            val next = old + line
            if (next.size > MAX_LINES) next.subList(next.size - MAX_LINES, next.size).toList() else next
        }
    }

    private companion object {
        const val MAX_LINES = 3_000
        const val MAX_HISTORY = 200
    }
}
