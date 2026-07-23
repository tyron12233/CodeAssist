package dev.ide.build.jvm.run

import dev.ide.build.engine.ControlledExit
import dev.ide.build.engine.InterpretRunRequest
import dev.ide.build.engine.ProgramInterpreter
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.StreamingTextDecoder
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
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
 * have finished. Cancellation asks the VM to stop (its loop unwinds even a tight compute loop) and interrupts
 * the whole group (to break a blocked stdin read, `sleep`, `wait`, or `join` on any thread).
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
        val vm = Vm(source, InterpretPolicy.DEFAULT, RunBridge(javaClass.classLoader), peerFactory, SPAWNED_STACK_BYTES)
        val outcome = Outcome()
        // A dedicated group so every Thread the program starts (a real host thread, created by the creating
        // thread) inherits it and can be interrupted together on Stop.
        val group = ThreadGroup("interp-run")
        val thread = Thread(group, {
            try {
                runMain(vm, request.mainClass.replace('.', '/'), request.args)
            } catch (t: Throwable) {
                outcome.error = t
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
                        vm.requestCancel()
                        group.interrupt()
                        runCatching { thread.join(2000) }
                    }
                }
                thread.start()
                try {
                    // Wait for main, then for the non-daemon threads it started (JVM exit semantics).
                    runInterruptible {
                        thread.join()
                        awaitNonDaemonThreads(group)
                    }
                } finally {
                    killer.cancel()
                }
            }
        } finally {
            System.setOut(savedOut); System.setErr(savedErr); System.setIn(savedIn)
            jars.forEach { runCatching { it.close() } }
        }

        val code = exitCodeFor(outcome, io)
        io.exited(code)
        code
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
        else -> { report(e, io); 1 }
    }

    private fun report(t: Throwable, io: ProgramIo) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        io.stdout("\nException in thread \"main\" $sw")
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

    /** The thrown outcome of the program thread, published to the run coroutine by `Thread.join`. */
    private class Outcome {
        @JvmField var error: Throwable? = null
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

    private companion object {
        val ARGS_DESC = listOf("[Ljava/lang/String;")
        // Interpreted recursion runs on this thread's host stack, so give it plenty of headroom (matches the
        // old in-process dex runner's user-main thread).
        const val STACK_BYTES = 16L * 1024 * 1024
        // A Thread the program starts also interprets on its own host stack; give it a generous (if smaller
        // than main's) stack so deep recursion on a worker doesn't overflow far shallower than on main, while
        // bounding the reservation for a program that spawns many threads.
        const val SPAWNED_STACK_BYTES = 8L * 1024 * 1024
    }
}
