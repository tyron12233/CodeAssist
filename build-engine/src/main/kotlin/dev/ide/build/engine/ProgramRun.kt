package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Marker for tasks that must run every time (never up-to-date) — like Gradle's `JavaExec`/`run`. */
interface AlwaysRun

/**
 * The running program's standard I/O for an interactive console run, supplied by the host. [stdout] receives
 * the program's combined stdout/stderr as raw text — chunks may be partial lines, so a prompt with no trailing
 * newline (`print("Enter name: ")`) shows immediately. [stdin] is the program's standard input; reads block
 * until the host feeds input or signals end-of-input. [started]/[exited] bracket the program's execution so
 * the UI can enable input while it runs and record the exit code.
 */
interface ProgramIo {
    fun stdout(text: String)
    val stdin: InputStream
    fun started() {}
    fun exited(code: Int) {}

    /**
     * One frame of a WINDOWED program's UI: raw RGBA_8888 pixels, row-major, at [path].
     *
     * A PATH rather than the bytes, because on device this crosses two process boundaries (the program runs in
     * `:preview`, the run is driven from `:build`, the screen is in the IDE) and a window's worth of pixels is
     * megabytes, well past what a Binder transaction carries. Bulk over the shared filesystem, control over
     * IPC, the same split the Compose preview uses. The host reads and deletes the file.
     *
     * [seq] increases monotonically so a host drawing slower than the program repaints can drop a stale frame.
     * A console run never calls this, hence the no-op default.
     */
    fun frame(path: String, width: Int, height: Int, seq: Long) {}

    /**
     * Announces that this program has a window, handing the host the way to drive it. Called once, before the
     * first [frame]. A host that cannot show a window can ignore it and will simply receive frames nobody
     * looks at.
     */
    fun windowed(window: RunWindow) {}
}

/**
 * The handle a host drives a windowed program with. Coordinates are in the frame's own pixel space, which the
 * host maps from wherever it drew the frame.
 */
interface RunWindow {
    /** A pointer event; [action] is one of [RunPointer]'s constants. */
    fun pointer(action: Int, x: Float, y: Float)

    /** A key event; [action] is one of [RunKey]'s constants, [keyCode] an AWT `VK_` code, and [keyChar] the
     *  character typed or [RunKey.CHAR_UNDEFINED] for a key that produces none. */
    fun key(action: Int, keyCode: Int, keyChar: Char)

    /** A wheel or two-finger scroll at ([x], [y]); [notches] is positive when the content should move down.
     *  It reaches the deepest scrollable thing under the pointer, as it does in AWT. */
    fun scroll(x: Float, y: Float, notches: Int)

    /** The size the host will draw at, so the program's window is painted at exactly that size instead of
     *  being scaled. Called whenever the surface resizes. */
    fun resize(widthPx: Int, heightPx: Int)
}

/** Pointer actions, named here so neither the UI nor the toolkit has to speak Android's `MotionEvent`. */
object RunPointer {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
}

/** Key actions, and the "no character" sentinel AWT spells `KeyEvent.CHAR_UNDEFINED`. */
object RunKey {
    const val DOWN = 0
    const val UP = 1
    const val CHAR_UNDEFINED = '\uFFFF'
}

/**
 * Default [ProgramIo] for a non-interactive run: re-splits the program's raw output into lines for the build
 * [log] and offers an empty (immediately end-of-input) stdin. Used when no interactive console is attached,
 * preserving the original line-oriented build-log behavior.
 */
internal class LoggingProgramIo(private val log: (String) -> Unit) : ProgramIo {
    private val pending = StringBuilder()
    override val stdin: InputStream = object : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    @Synchronized
    override fun stdout(text: String) {
        pending.append(text)
        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            log(pending.substring(0, nl).trimEnd('\r'))
            pending.delete(0, nl + 1)
            nl = pending.indexOf("\n")
        }
    }

    @Synchronized
    override fun exited(code: Int) {
        if (pending.isNotEmpty()) {
            log(pending.toString().trimEnd('\r')); pending.setLength(0)
        }
    }
}

/**
 * Runs a compiled console program by INTERPRETING its bytecode (the bytecode VM), rather than loading it into
 * the host class loader (impossible on ART without dynamic dex loading) or forking a `java`/`dalvikvm` process.
 * Supplied by the host over `:jvm-interp`; one implementation serves both desktop and device. The program runs
 * in this process, mediated entirely by the VM's bridge: `System.exit` is caught (ends the run, not the IDE),
 * network/file/reflection/exec calls consult the permission broker ([Guards]), and stdout/stdin are wired to
 * the run's [ProgramIo] — so there is no dex step and no class loading of the user's or the libraries' code.
 */
fun interface ProgramInterpreter {
    /** Interpret [request]'s `main`, streaming stdout/stderr to [io] and reading stdin from it, and return the
     *  exit code (0 on a normal return, the argument of a `System.exit`, or non-zero on an uncaught error).
     *  Cancellable: on coroutine cancellation the interpreter unwinds even a tight compute loop. */
    suspend fun run(request: InterpretRunRequest, io: ProgramIo): Int
}

/**
 * One console run to interpret. [classpath] is the module's runtime classpath — its compiled class output
 * directories plus the dependency/library jars, exactly as a JVM would receive it, with no dexing. [mainClass]
 * is the entry-point FQN; [instanceMain] is true when it has no static `main` (a `class T { fun main() }`), so
 * the interpreter constructs the class before calling it. [args] are the program arguments.
 */
class InterpretRunRequest(
    val classpath: List<Path>,
    val mainClass: String,
    val instanceMain: Boolean = false,
    val args: List<String> = emptyList(),
)

/**
 * `run`: interpret the module's compiled program on the bytecode VM — the sole console-run task (the desktop
 * `java`-fork and on-device dex paths are gone). Always runs (an [AlwaysRun]); the injected [interpreter]
 * streams output through [programIo] and returns the exit code.
 */
class InterpretExecTask(
    override val name: TaskName,
    private val mainClass: String,
    private val runtimeClasspath: () -> List<Path>,
    private val interpreter: ProgramInterpreter,
    private val programArgs: List<String> = emptyList(),
    private val instanceMain: Boolean = false,
    private val programIo: ProgramIo? = null,
) : Task, AlwaysRun {
    override val inputs: TaskInputs get() = TaskInputsImpl()
    override val outputs: TaskOutputs get() = TaskOutputsImpl()

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.logger()("> Run $mainClass")
        val io = programIo ?: LoggingProgramIo(ctx.logger())
        val cp = runtimeClasspath().filter { Files.exists(it) }
        val code = interpreter.run(InterpretRunRequest(cp, mainClass, instanceMain, programArgs), io)
        return if (code == 0) TaskResult.Success else TaskResult.Failed("$mainClass exited with code $code")
    }
}
