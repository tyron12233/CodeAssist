package dev.ide.build.jvm.run

import dev.ide.build.engine.ControlledExit
import dev.ide.build.engine.InterpretRunRequest
import dev.ide.build.engine.ProgramInterpreter
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.StreamingTextDecoder
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerDispatch
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.PeerSpec
import dev.ide.jvm.Vm
import dev.ide.jvm.VmInterruptedException
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.interpretedConstructors
import dev.ide.jvm.interpretedMethods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Runs a module's compiled console program by interpreting its bytecode on the [Vm], the single console-run
 * engine on both desktop and device. A FRESH VM is built per run, so the program's statics start clean each
 * time (as reloading the classes used to give). The user's and the libraries' classes are read from the
 * runtime classpath and interpreted; only the platform and standard-library namespaces are bridged to real
 * code (see [InterpretPolicy.DEFAULT]) — nothing on the classpath is loaded into the host class loader or
 * dexed, which is the point of the interpreter path.
 *
 * The bridge is a [RunBridge]: it catches `System.exit` and mediates sandbox-sensitive calls through the
 * permission broker. The program's standard I/O is wired by redirecting the process-global `System.out`/`err`/
 * `in` to the run's [ProgramIo] for the duration of the run, so BOTH interpreted code and bridged
 * standard-library I/O (e.g. Kotlin's `readln`, which reads the real `System.in`) reach the run console. Runs
 * are sequential so the global redirect is safe, and the IDE's own log sink captured the real streams at
 * startup, so its logs never leak into the program's output. The program's `main` runs on a dedicated
 * large-stack thread in its own [ThreadGroup] (interpreted recursion uses the host stack); a `Thread` the
 * program starts is a REAL host thread that inherits the group and runs interpreted bytecode concurrently on
 * the multi-threaded [Vm]. As on a real JVM, the run ends when `main` AND every non-daemon thread it started
 * have finished, AND every window it opened has closed (see [ProgramWindows]). That last one is what a GUI
 * program still has outstanding when `main` returns, since it lives on afterwards on the AWT event thread,
 * which belongs to the host rather than to this group. Cancellation disposes those windows, asks the VM to
 * stop (its loop unwinds even a tight compute loop), and interrupts the whole group (to break a blocked stdin
 * read, `sleep`, `wait`, or `join` on any thread). What those threads THROW is the group's business too: an
 * exception escaping one of them is printed to the run console and the program carries on, as on a real JVM,
 * rather than reaching the process-wide handler (see [ProgramThreadGroup]).
 *
 * [peerFactory] produces the real subclasses that let platform code invoke an interpreted object's overrides
 * (e.g. a `Comparator` handed to `Collections.sort`). Desktop uses the default ASM factory; a device host
 * injects one that dexes the generated peer.
 */
class VmProgramInterpreter(
    private val peerFactory: PeerFactory = AsmPeerFactory(),
) : ProgramInterpreter {

    override suspend fun run(request: InterpretRunRequest, io: ProgramIo): Int = withContext(Dispatchers.IO) {
        val jars = ArrayList<JarFile>()
        val source = classpathSource(request.classpath, jars)
        // Windows the program opens keep the run alive past `main`; they are created either as a peer (the
        // program's own `class MyFrame extends JFrame`) or through the bridge (a plain `new JFrame()`), so both
        // producers report into the same tracker.
        val windows = ProgramWindows()
        val bridge = RunBridge(javaClass.classLoader, windows)
        val vm = Vm(source, InterpretPolicy.DEFAULT, bridge, WindowTrackingPeers(peerFactory, windows), SPAWNED_STACK_BYTES)
        val outcome = Outcome()
        // A dedicated group so every Thread the program starts (a real host thread, created by the creating
        // thread) inherits it and can be interrupted together on Stop, and so what those threads throw is
        // handled by the run rather than by the process (see [ProgramThreadGroup]).
        val group = ProgramThreadGroup(vm, windows, outcome, io)
        val thread = Thread(group, {
            try {
                runMain(vm, request.mainClass.replace('.', '/'), request.args)
            } catch (t: Throwable) {
                outcome.record(t)
            }
        }, "program-main", STACK_BYTES).apply { isDaemon = true }

        io.started()
        // Redirect the process-global console streams to the run for the duration; both interpreted `System.out`
        // and bridged standard-library I/O then reach the run console. Runs are sequential, so this is safe.
        val programOut = PrintStream(ProgramOut(io), true, "UTF-8")
        val savedOut = System.out; val savedErr = System.err; val savedIn = System.`in`
        System.setOut(programOut); System.setErr(programOut); System.setIn(io.stdin)
        try {
            coroutineScope {
                // Wakes on cancellation (Stop): ask the VM to unwind every thread's instruction loop and
                // interrupt the whole group (main + any thread the program started) so a blocked stdin read,
                // sleep, wait, or join returns too, then give them a moment to finish.
                val killer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        // Ahead of the interrupt, so the InterruptedExceptions it raises on the program's
                        // threads read as the run ending rather than as program failures to report.
                        group.cancelling = true
                        windows.disposeAll()
                        vm.requestCancel()
                        group.interrupt()
                        runCatching { joinProgramThreads(group, thread, TEARDOWN_JOIN_MS) }
                    }
                }
                thread.start()
                try {
                    // Wait for main, then for the non-daemon threads it started (JVM exit semantics), then for
                    // the program's windows: a GUI program's `main` returns at `setVisible(true)` and the
                    // program lives on afterwards on the AWT event thread, which is outside this group.
                    runInterruptible {
                        thread.join()
                        awaitNonDaemonThreads(group)
                        if (outcome.error !is ControlledExit) awaitWindowsClosed(windows)
                    }
                } finally {
                    killer.cancel()
                }
            }
        } finally {
            // The program's threads are already unwound here: `killer.cancel()` above runs the killer's
            // `finally` (VM cancel + group interrupt) and `coroutineScope` waits for it to finish. Its windows
            // are not, on the one path that skips the wait: a `System.exit`, which on a real JVM takes the
            // windows with it. Nothing the program owns may outlive the run and reach a jar the next lines
            // close (a class it had not loaded yet then fails with `IllegalStateException: zip file closed`)
            // or a console that has already been detached.
            windows.disposeAll()
            System.setOut(savedOut); System.setErr(savedErr); System.setIn(savedIn)
            jars.forEach { runCatching { it.close() } }
        }

        val code = exitCodeFor(outcome, io)
        io.exited(code)
        code
    }

    /**
     * Wait, within [budgetMs] in total, for every thread the program started to finish: `main` plus anything it
     * spawned, daemon threads included. Called after the group has been interrupted and the VM asked to unwind,
     * so this is the wait for that request to take effect, not the request itself.
     *
     * Joining only `main` was enough for the run to report the right exit code, but a daemon the program left
     * behind keeps interpreting for as long as it takes to notice the cancel, and by then the run has closed the
     * classpath jars underneath it: the first not-yet-loaded class it touches fails with "zip file closed".
     * Bounded rather than unbounded, so a thread that never reaches a cancellation point cannot hold the run
     * open; the budget is the same one `main` already had.
     */
    private fun joinProgramThreads(group: ThreadGroup, main: Thread, budgetMs: Long) {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        fun leftMs() = (deadline - System.nanoTime()) / 1_000_000
        while (true) {
            val slice = leftMs()
            if (slice <= 0) return
            val snapshot = arrayOfNulls<Thread>(group.activeCount() + 8)
            val n = group.enumerate(snapshot, true)
            val alive = ((0 until n).mapNotNull { snapshot[it] } + main)
                .filter { it.isAlive && it !== Thread.currentThread() }
                .distinct()
            if (alive.isEmpty()) return
            // A short slice per thread so a drained group returns promptly and one slow thread cannot spend the
            // whole budget while the others are already gone. `join(0)` would wait forever, hence the floor.
            alive.forEach { t -> runCatching { t.join(leftMs().coerceIn(1, 100)) } }
        }
    }

    /** Block until every non-daemon thread the program started (its `Thread`s live in [group]) has finished,
     *  mirroring the JVM, which keeps running until the last non-daemon thread exits. `main` is already joined
     *  and is itself a daemon here, so it is not counted. On Stop the group is interrupted and the VM unwinds,
     *  so those threads die and this returns; an interrupt of the waiting (run) thread propagates out to
     *  cancellation. */
    private fun awaitNonDaemonThreads(group: ThreadGroup) {
        while (true) {
            val snapshot = arrayOfNulls<Thread>(group.activeCount() + 8)
            val n = group.enumerate(snapshot, true)
            val pending = (0 until n).mapNotNull { snapshot[it] }
                .filter { it.isAlive && !it.isDaemon && it !== Thread.currentThread() }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    /** Block while the program still has a window on screen, so a GUI run lasts as long as its UI does. Polled
     *  rather than event-driven: the alternative is an AWT window listener, and this module is shared with the
     *  device, where `java.awt` does not exist. Costs one reflective `isDisplayable` per tracked window per
     *  interval, and a program with no windows (every console run) never enters the loop. An interrupt (Stop,
     *  which disposes the windows first) propagates out as cancellation. */
    private fun awaitWindowsClosed(windows: ProgramWindows) {
        while (windows.liveCount() > 0) Thread.sleep(WINDOW_POLL_MS)
    }

    /** Resolve and invoke the program entry point: prefer a static `main` (with or without a `String[]`), else
     *  construct the class and call its instance `main` (the `class T { fun main() }` form). */
    private fun runMain(vm: Vm, internalName: String, args: List<String>) {
        val fqn = internalName.replace('/', '.')
        val mains = vm.interpretedMethods(fqn).filter { it.name == "main" && !it.isConstructor }
        fun pick(static: Boolean): VmMethodView? =
            mains.firstOrNull { it.isStatic == static && it.paramDescriptors == ARGS_DESC }
                ?: mains.firstOrNull { it.isStatic == static && it.paramDescriptors.isEmpty() }

        var entry = pick(static = true)
        var receiver: Any? = null
        if (entry == null) {
            entry = pick(static = false)
            if (entry != null) {
                val ctor = vm.interpretedConstructors(fqn).firstOrNull { it.paramDescriptors.isEmpty() }
                    ?: throw IllegalStateException("no no-argument constructor to run instance main on $fqn")
                receiver = ctor.invoke(null, emptyList())
            }
        }
        if (entry == null) throw IllegalStateException("no runnable main method on $fqn")

        val callArgs = if (entry.paramDescriptors.isEmpty()) emptyList() else listOf(args.toTypedArray())
        entry.invoke(receiver, callArgs)
    }

    private fun exitCodeFor(o: Outcome, io: ProgramIo): Int = when (val e = o.error) {
        null -> 0
        is ControlledExit -> e.code // the program called System.exit / Runtime.exit|halt
        is VmInterruptedException -> 130 // cancelled mid-run
        is StackOverflowError -> { io.stdout("\nStackOverflowError: the program recursed too deeply.\n"); 1 }
        is OutOfMemoryError -> { io.stdout("\nOutOfMemoryError: the program ran out of memory.\n"); 1 }
        else -> { report("main", e, io); 1 }
    }

    /** Reads `.class` bytes from the run's classpath: directories first (the module's own output), then the
     *  library jars. The opened jars are collected into [jarsOut] so the run can close them afterward. */
    private fun classpathSource(classpath: List<Path>, jarsOut: MutableList<JarFile>): ClassBytesSource {
        val dirs = classpath.filter { Files.isDirectory(it) }
        val jars = classpath.filter { Files.isRegularFile(it) }.mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }
        jarsOut.addAll(jars)
        return ClassBytesSource { internalName ->
            val rel = "$internalName.class"
            dirs.firstNotNullOfOrNull { d -> d.resolve(rel).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) } }
                ?: jars.firstNotNullOfOrNull { jar -> jar.getJarEntry(rel)?.let { e -> jar.getInputStream(e).use { it.readBytes() } } }
        }
    }

    /** The thrown outcome of the program, published to the run coroutine by `Thread.join`. Written by `main`
     *  and, for a `System.exit` off it, by [ProgramThreadGroup], hence volatile. */
    private class Outcome {
        @Volatile @JvmField var error: Throwable? = null

        /** Keep the FIRST failure: the teardown a `System.exit` on a worker triggers interrupts `main`, and the
         *  InterruptedException that raises there must not displace the exit code the program asked for. */
        @Synchronized fun record(t: Throwable) { if (error == null) error = t }
    }

    /**
     * The program's thread group, which also decides what happens when one of its threads throws.
     *
     * A `Thread` the program starts is a REAL host thread, so an exception escaping its `run` goes to the
     * process-wide handler (on Android the system killer), which takes the IDE's build process down with the
     * user's bug (reported: a program whose `thread { Thread.sleep(...) }` was interrupted as the run ended
     * killed `com.tyron.code:build`). A real JVM prints the trace and lets the rest of the program carry on,
     * which is what this does, into the run console instead of the IDE's log.
     */
    private class ProgramThreadGroup(
        private val vm: Vm,
        private val windows: ProgramWindows,
        private val outcome: Outcome,
        private val io: ProgramIo,
    ) : ThreadGroup("interp-run") {

        /** Set before the group is interrupted, so the interrupt the run itself causes is not reported as a
         *  program failure. */
        @Volatile var cancelling = false

        override fun uncaughtException(t: Thread, e: Throwable) {
            when (val error = surfaced(e)) {
                // `System.exit` off the main thread ends the whole program on a real JVM, not just the thread
                // that called it: record the code, then unwind everything the way Stop does.
                is ControlledExit -> {
                    outcome.record(error)
                    cancelling = true
                    windows.disposeAll()
                    vm.requestCancel()
                    interrupt()
                }
                // The run's own cancellation reaching a program thread: the Stop the user asked for.
                is VmInterruptedException -> {}
                is InterruptedException -> if (!cancelling) report(t.name, error, io)
                else -> report(t.name, error, io)
            }
        }
    }

    /** Turns the program's raw output bytes into text and forwards it to the run console; the decoder carries
     *  an incomplete trailing UTF-8 sequence across writes so a multi-byte char never splits. */
    private class ProgramOut(private val io: ProgramIo) : OutputStream() {
        private val decoder = StreamingTextDecoder()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val text = decoder.decode(b, off, len)
            if (text.isNotEmpty()) io.stdout(text)
        }
    }

    /**
     * A [PeerFactory] that reports every peer it builds to [windows]. A program's own window class
     * (`class MyFrame extends JFrame`) reaches the platform as a generated peer, not through the bridge's
     * `construct`, so this is the only place that instance can be seen.
     */
    private class WindowTrackingPeers(
        private val delegate: PeerFactory,
        private val windows: ProgramWindows,
    ) : PeerFactory by delegate {
        override fun createPeer(
            vmObject: Any,
            spec: PeerSpec,
            dispatch: PeerDispatch,
            superConstructorDescriptor: String,
            superConstructorArgs: List<Any?>,
        ): Any = delegate.createPeer(vmObject, spec, dispatch, superConstructorDescriptor, superConstructorArgs)
            .also { windows.record(it) }
    }

    private companion object {
        val ARGS_DESC = listOf("[Ljava/lang/String;")
        /** How often [awaitWindowsClosed] rechecks. Long enough to be free, short enough that the run console
         *  flips to finished the moment the user closes the window. */
        const val WINDOW_POLL_MS = 100L
        /** Total time [joinProgramThreads] waits for the program's threads to notice the cancel and exit. */
        const val TEARDOWN_JOIN_MS = 2000L
        // Interpreted recursion runs on this thread's host stack, so give it plenty of headroom (matches the
        // old in-process dex runner's user-main thread).
        const val STACK_BYTES = 16L * 1024 * 1024
        // A Thread the program starts also interprets on its own host stack; give it a generous (if smaller
        // than main's) stack so deep recursion on a worker doesn't overflow far shallower than on main, while
        // bounding the reservation for a program that spawns many threads.
        const val SPAWNED_STACK_BYTES = 8L * 1024 * 1024
    }
}

/** Print an uncaught program exception to the run console, in the form a JVM prints it. */
private fun report(threadName: String, t: Throwable, io: ProgramIo) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    io.stdout("\nException in thread \"$threadName\" $sw")
}

/** What a program thread actually threw. An interpreted lambda or peer reaches platform code as a
 *  [java.lang.reflect.Proxy], whose generated method wraps a checked throwable its interface does not declare;
 *  that wrapper is an artifact of how the VM hands interpreted code to the platform, so it is stripped before
 *  the throwable is reported or acted on. */
private fun surfaced(e: Throwable): Throwable =
    if (e is UndeclaredThrowableException) e.undeclaredThrowable ?: e else e
