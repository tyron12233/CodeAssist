package dev.ide.interp.api

import dev.ide.platform.ServiceKey
import java.nio.file.Path

/**
 * Runs the user's project code inside the IDE, so a plugin can preview or drive a framework the IDE knows
 * nothing about (see docs/plugin-interpreter.md).
 *
 * Two kinds of session, matching the two interpreters the IDE has:
 *
 *  - [openSource] interprets Kotlin **source**, which is what makes an edit-to-render loop possible: there is
 *    no compile and no dex step, so a session can be opened against the buffer the user is typing in. Lowering
 *    source to something runnable needs the project's analyzers and indexes, so it is the host's job
 *    ([lower]); a plugin carries the resulting [LoweredProgram] from one to the other.
 *  - [openBytecode] runs **compiled** classes on the bytecode VM, for a plugin that builds first (its Run row
 *    already produced class files) or that wants to reach code with no source in the project.
 *
 * Resolved from the application container, so a plugin reaches it from its engine facet:
 *
 * ```kotlin
 * class MyPlugin : Plugin {
 *     private lateinit var services: ServiceLookup
 *     override fun register(reg: PluginRegistration) { services = reg.appServices }
 *     // later, from a callback:
 *     private fun interpreter() = services.getServiceOrNull(CODE_INTERPRETER)
 * }
 * ```
 *
 * Resolve it lazily, from a callback rather than from `register`: the service reads whichever project is open,
 * and at registration time none is.
 *
 * Everything here is synchronous and runs on the calling thread. Interpretation is bounded rather than
 * unbounded: the source interpreter aborts a call that exceeds its wall-clock deadline or recursion depth, and
 * a bytecode session can be cancelled ([BytecodeSession.requestCancel]). It is not, however, isolated in
 * another process the way the built-in Compose preview is, so a session's work is the IDE's work. Keep a
 * render call short, and prefer driving one frame per edit over an open loop.
 */
interface CodeInterpreter {

    /**
     * Lower the Kotlin declaration named by [request] to a runnable program, or report why it cannot be.
     *
     * "Lowering" is the resolution pass the interpreter needs: every call site resolved to an exact target,
     * operators desugared, and the reachable declarations of the file and its dependency modules pulled in.
     * The result is opaque; a plugin passes it to [openSource] and reads [LoweredProgram.problems] to explain
     * a failure to the user.
     */
    fun lower(request: LowerRequest): LowerResult

    /**
     * Open a session over a [LoweredProgram]: interpret [program]'s functions and construct its classes.
     *
     * Sessions are cheap to open and hold no project locks, so re-opening one per edit is the intended usage
     * (that is how the Compose preview handles a keystroke). Dispose the previous one first, or leave it to
     * the plugin's unload.
     */
    fun openSource(program: LoweredProgram, config: InterpretConfig = InterpretConfig()): SourceSession

    /**
     * Open a session over compiled classes on the bytecode VM. [BytecodeConfig.classpath] is read as an
     * ordinary JVM classpath (directories and jars); no dexing happens and no class loader is handed the
     * user's code.
     */
    fun openBytecode(config: BytecodeConfig): BytecodeSession
}

/** The [CodeInterpreter], registered at application scope. Absent on a host that wires no interpreter. */
val CODE_INTERPRETER = ServiceKey<CodeInterpreter>("platform.codeInterpreter")

/**
 * What to lower: a declaration in one Kotlin file of the open project.
 *
 * [file] is an absolute path in the open project. [text] is the live editor buffer, which is what makes the
 * result reflect unsaved edits; null lowers what is on disk. [entry] names a top-level function or a class
 * (simple name or fully-qualified), and [arity] picks between overloads of a function by value-parameter count.
 *
 * [strict] refuses a program in which any reachable declaration failed to lower, instead of running it and
 * skipping the gaps. Leave it false for a preview, where one unsupported statement should cost that statement
 * rather than the whole render, and set it true when a partial run would be misleading.
 */
class LowerRequest(
    val file: Path,
    val entry: String,
    val text: String? = null,
    val arity: Int = 0,
    val strict: Boolean = false,
)

/** The outcome of [CodeInterpreter.lower]. */
sealed interface LowerResult {

    /** Lowered and runnable. */
    class Lowered(val program: LoweredProgram) : LowerResult

    /**
     * Not lowerable *yet*, for a reason that will pass on its own: the workspace is still indexing, no project
     * is open, or the file's libraries are still attaching. [message] is written for the user. Retry; do not
     * report it as a failure, which is what the built-in previews get wrong when they do not distinguish this.
     */
    class NotReady(val message: String) : LowerResult

    /**
     * Not lowerable. [problems] explains why, one entry per reason, already phrased for display: a syntax
     * error in the file, an unresolved call the interpreter cannot execute, an entry point that is not there.
     */
    class Failed(val problems: List<String>) : LowerResult
}

/**
 * A lowered program: the entry declaration plus every declaration reachable from it, across files and
 * dependency modules.
 *
 * Deliberately opaque. What is inside is the resolver-to-interpreter contract, which changes whenever lowering
 * is fixed or extended; freezing it as plugin API would mean a plugin compiled against one version failing at
 * its first call against the next. A plugin needs to carry the handle and explain it, which is what these
 * members are for.
 */
interface LoweredProgram {

    /**
     * The entry as lowered: `name/arity` for a function, the fully-qualified name for a type. Pass it to
     * [SourceSession.call] or [SourceSession.instantiate] respectively, whichever the request named.
     */
    val entry: String

    /** Fully-qualified names of the source types this program can construct ([SourceSession.instantiate]). */
    val types: List<String>

    /**
     * What lowered imperfectly, phrased for display, empty when the program is clean. A non-empty list is not
     * a failure: with [LowerRequest.strict] off, these are the statements a run will skip. Show them the way
     * the Compose preview shows its problem chip.
     */
    val problems: List<String>
}
