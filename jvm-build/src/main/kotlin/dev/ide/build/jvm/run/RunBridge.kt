package dev.ide.build.jvm.run

import dev.ide.build.engine.ControlledExit
import dev.ide.build.engine.GuardCategory
import dev.ide.build.engine.Guards
import dev.ide.jvm.NativeBridge
import dev.ide.jvm.ReflectiveBridge
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.Socket
import java.net.URL

/**
 * The [NativeBridge] a console run interprets against. It forwards to a [ReflectiveBridge] (the real platform +
 * standard library) but mediates the boundary two ways, so the run-sandbox lives here instead of in bytecode
 * instrumentation:
 *
 *  - **Exit** — `System.exit` / `Runtime.exit` / `Runtime.halt` throw [ControlledExit] instead of terminating
 *    the IDE process; the program interpreter catches it to end the *run* with that code.
 *  - **Sandbox** — network / file / reflection / process calls consult the permission [Guards] broker before
 *    proceeding, throwing `SecurityException` on denial (ends the run). A curated set, not a hardened sandbox.
 *  - **Windows**: a top-level window the program constructs is recorded in [windows], so a GUI run ends when
 *    the last one closes rather than when `main` returns, and `EXIT_ON_CLOSE` is clamped (see
 *    [clampedCloseOperation]) so closing that window cannot take the IDE with it.
 *
 * Standard I/O is NOT handled here: the interpreter redirects the process-global `System.out`/`err`/`in` to
 * the run console for the duration of the run, so both interpreted code and bridged standard-library I/O
 * (e.g. Kotlin's `readln`, which reads the real `System.in`) observe it.
 */
internal class RunBridge(
    loader: ClassLoader,
    private val windows: ProgramWindows = ProgramWindows(),
) : NativeBridge {
    // A console run propagates lambda failures (no proxyExceptionSink) — unlike a preview, which degrades them.
    private val delegate = ReflectiveBridge(loader)

    override fun invokeStatic(owner: String, name: String, descriptor: String, args: List<Any?>): Any? {
        if (owner == "java/lang/System" && name == "exit" && descriptor == "(I)V") throw ControlledExit(args[0] as Int)
        enforceStatic(owner, name, args)
        return delegate.invokeStatic(owner, name, descriptor, args)
    }

    override fun invokeVirtual(receiver: Any, name: String, descriptor: String, args: List<Any?>): Any? {
        if (receiver is Runtime && (name == "exit" || name == "halt") && descriptor == "(I)V") throw ControlledExit(args[0] as Int)
        enforceVirtual(receiver, name)
        val callArgs = clampedCloseOperation(receiver.javaClass, name, descriptor, args) ?: args
        return delegate.invokeVirtual(receiver, name, descriptor, callArgs)
    }

    override fun getStatic(owner: String, name: String, descriptor: String): Any? =
        delegate.getStatic(owner, name, descriptor)

    override fun putStatic(owner: String, name: String, descriptor: String, value: Any?) =
        delegate.putStatic(owner, name, descriptor, value)

    override fun getField(receiver: Any, name: String, descriptor: String): Any? =
        delegate.getField(receiver, name, descriptor)

    override fun putField(receiver: Any, name: String, descriptor: String, value: Any?) =
        delegate.putField(receiver, name, descriptor, value)

    override fun construct(owner: String, descriptor: String, args: List<Any?>): Any? {
        enforceConstruct(owner, args)
        return delegate.construct(owner, descriptor, args).also { windows.record(it) }
    }

    // ---- sandbox (mirrors the curated set the old SandboxGuard bytecode rewrite covered) ----

    private fun enforceStatic(owner: String, name: String, args: List<Any?>) {
        when {
            owner == "java/lang/Class" && name == "forName" ->
                Guards.enforce(GuardCategory.REFLECTION, args.firstOrNull()?.toString() ?: "Class.forName")
            owner == "java/net/InetAddress" && (name == "getByName" || name == "getAllByName") ->
                Guards.enforce(GuardCategory.NETWORK, "dns:${args.firstOrNull()}")
            owner == "java/nio/file/Files" -> filesCategory(name)?.let { Guards.enforce(it, "Files.$name(${args.firstOrNull()})") }
        }
    }

    private fun enforceVirtual(receiver: Any, name: String) {
        when {
            receiver is URL && (name == "openConnection" || name == "openStream") -> Guards.enforce(GuardCategory.NETWORK, receiver.toString())
            receiver is Socket && name == "connect" -> Guards.enforce(GuardCategory.NETWORK, receiver.toString())
            receiver is InetAddress && name == "getByName" -> Guards.enforce(GuardCategory.NETWORK, receiver.toString())
            receiver is Method && name == "invoke" -> Guards.enforce(GuardCategory.REFLECTION, receiver.toString())
            receiver is Constructor<*> && name == "newInstance" -> Guards.enforce(GuardCategory.REFLECTION, receiver.toString())
            receiver is AccessibleObject && name == "setAccessible" -> Guards.enforce(GuardCategory.REFLECTION, receiver.toString())
            receiver is Runtime && name == "exec" -> Guards.enforce(GuardCategory.EXEC, "Runtime.exec")
            receiver is ProcessBuilder && name == "start" -> Guards.enforce(GuardCategory.EXEC, receiver.command().joinToString(" "))
        }
    }

    private fun enforceConstruct(owner: String, args: List<Any?>) {
        val detail = args.firstOrNull()?.toString() ?: owner
        when (owner) {
            "java/io/FileInputStream", "java/io/FileReader" -> Guards.enforce(GuardCategory.FILE_READ, detail)
            "java/io/FileOutputStream", "java/io/FileWriter" -> Guards.enforce(GuardCategory.FILE_WRITE, detail)
            "java/io/RandomAccessFile" -> {
                val write = (args.getOrNull(1) as? String) != "r"
                Guards.enforce(if (write) GuardCategory.FILE_WRITE else GuardCategory.FILE_READ, detail)
            }
            "java/net/Socket" -> Guards.enforce(GuardCategory.NETWORK, detail)
        }
    }

    /** The guard category for a `java.nio.file.Files.<name>` call, or null when it is not a mediated operation. */
    private fun filesCategory(name: String): GuardCategory? = when {
        name.startsWith("read") || name == "lines" || name == "newInputStream" || name == "newBufferedReader" -> GuardCategory.FILE_READ
        name.startsWith("write") || name.startsWith("delete") || name == "newOutputStream" || name == "newBufferedWriter" -> GuardCategory.FILE_WRITE
        else -> null
    }
}

/**
 * Rewritten arguments for `setDefaultCloseOperation(EXIT_ON_CLOSE)` on a window, or null to pass the call
 * through untouched.
 *
 * `EXIT_ON_CLOSE` is the first line of nearly every Swing program, and it is the one exit path the bridge's
 * `System.exit` interception cannot see: Swing calls `System.exit` from inside `java.desktop`, on the
 * event-dispatch thread, so no interpreted instruction is involved and [ControlledExit] is never thrown.
 * Closing the program's window would take the whole IDE with it (verified: the host process died silently with
 * code 0). Clamping to `DISPOSE_ON_CLOSE` gives the behavior a run wants anyway, since disposing the last
 * window is what ends the run.
 *
 * The constants are inlined rather than read from `javax.swing.WindowConstants` because this module is dexed
 * for the device, where the class does not exist; the receiver test walks class names for the same reason.
 */
internal fun clampedCloseOperation(
    receiverClass: Class<*>,
    name: String,
    descriptor: String,
    args: List<Any?>,
): List<Any?>? {
    if (name != "setDefaultCloseOperation" || descriptor != "(I)V") return null
    if (args.singleOrNull() != EXIT_ON_CLOSE) return null
    if (!isAwtWindow(receiverClass)) return null
    return listOf(DISPOSE_ON_CLOSE)
}

/** `javax.swing.WindowConstants.EXIT_ON_CLOSE`. */
private const val EXIT_ON_CLOSE = 3

/** `javax.swing.WindowConstants.DISPOSE_ON_CLOSE`. */
private const val DISPOSE_ON_CLOSE = 2
