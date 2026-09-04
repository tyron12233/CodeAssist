package dev.ide.interp.impl

import dev.ide.interp.Interpreter
import dev.ide.interp.InterpreterException
import dev.ide.interp.SourceObject
import dev.ide.interp.api.InterpretConfig
import dev.ide.interp.api.InterpretException
import dev.ide.interp.api.InterpretProblem
import dev.ide.interp.api.InterpretedObject
import dev.ide.interp.api.SourceSession

/**
 * A [SourceSession] over `:interp-core`'s tree-walking interpreter.
 *
 * One [Interpreter] per session, so the program's top-level state (a top-level `var`, an `object`'s
 * initialization) lives as long as the session and resets when a new one is opened. That is the same lifetime
 * the Compose preview gives a render, and it is why re-opening a session per edit gives a clean run while
 * calling repeatedly into one session accumulates state, which is what a plugin driving a frame loop wants.
 */
internal class SourceSessionImpl(
    private val program: LoweredKotlinProgram,
    config: InterpretConfig,
    sandbox: Set<String>,
) : SourceSession {

    private val hooks = SessionHooks.of(sandbox, config.strictSandbox, config.hooks)

    private val interpreter = Interpreter(
        functions = program.functions,
        classLoader = config.libraryLoader,
        classes = program.classes,
        tolerateGaps = config.tolerateGaps,
        hooks = hooks.hooks,
    )

    /** Problems the program carried out of lowering: reported on the session, so a caller that only reads
     *  [problems] still sees why part of the render is missing. Cleared with the rest, since a caller that
     *  clears has read them, and a fresh session re-reports them from the program anyway. */
    private var lowering: List<InterpretProblem> = program.problems.map {
        InterpretProblem(InterpretProblem.Severity.WARNING, it)
    }

    private var closed = false

    override val problems: List<InterpretProblem>
        get() = lowering + hooks.findings()

    override fun clearProblems() {
        lowering = emptyList()
        hooks.clearFindings()
    }

    override fun call(entry: String, args: List<Any?>): Any? {
        checkOpen()
        val fn = program.function(entry)
            ?: throw InterpretException(
                "`$entry` is not a function of this program (it has ${program.functions.keys.sorted()})"
            )
        return guarded { interpreter.call(fn, args) }
    }

    override fun instantiate(typeFqn: String, args: List<Any?>): InterpretedObject {
        checkOpen()
        program.type(typeFqn)
            ?: throw InterpretException(
                "`$typeFqn` is not a type of this program (it has ${program.types.sorted()})"
            )
        val obj = guarded { interpreter.newInstance(typeFqn, args) }
            ?: throw InterpretException("`$typeFqn` could not be constructed")
        return SourceInstance(interpreter, obj)
    }

    override fun dispose() {
        closed = true
    }

    private fun checkOpen() {
        if (closed) throw InterpretException("this session is closed")
    }
}

/** An [InterpretedObject] backed by a [SourceObject]. */
internal class SourceInstance(
    private val interpreter: Interpreter,
    private val obj: SourceObject,
) : InterpretedObject {

    override val typeFqn: String get() = obj.cls.fqn

    override val raw: Any get() = obj

    override fun call(method: String, args: List<Any?>): Any? =
        guarded { interpreter.invokeMember(obj, method, args) }

    override fun get(property: String): Any? = guarded { interpreter.readMember(obj, property) }

    override fun set(property: String, value: Any?) {
        guarded { interpreter.writeMember(obj, property, value) }
    }

    override fun <T : Any> proxy(iface: Class<T>): T = interpretedProxy(iface) { name, args ->
        interpreter.invokeMember(obj, name, args)
    }

    override fun toString(): String = "interpreted ${obj.cls.fqn}"
}

/**
 * Runs [body], translating the interpreter's own failures into the SPI's [InterpretException].
 *
 * A plugin catches one type, whichever interpreter answered and whichever way it gave up: an unsupported
 * construct, an unloadable library class, a sandbox refusal, the recursion or wall-clock bound, or an
 * exception the user's own code threw. The original is the `cause`, for a plugin that wants to look.
 */
internal inline fun <T> guarded(body: () -> T): T = try {
    body()
} catch (e: InterpreterException) {
    throw InterpretException(e.message ?: "interpretation failed", e)
} catch (e: InterpretException) {
    throw e
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e // cancellation is control flow, never a failure to report
} catch (e: VirtualMachineError) {
    // Never convert one of these. A swallowed StackOverflowError that is then formatted into a message is how
    // a caught error turns into a native crash on ART; the only safe thing is to let it keep unwinding.
    throw e
} catch (e: Throwable) {
    // The user's code threw, or real code it called did. Not a defect in the interpreter, and the message the
    // plugin should show is the throwable's own.
    throw InterpretException("${e.javaClass.simpleName}: ${e.message ?: "no message"}", e)
}
