package dev.ide.interp.api

import dev.ide.platform.Disposable

/**
 * A live run of the user's code: what a plugin calls into, and what holds the state those calls build up.
 *
 * A session is [Disposable], and disposing one is what releases the interpreted world it created (loaded
 * classes, static state, the VM's caches). Tie it to whatever owns it: `PluginRegistration.onDispose` for a
 * session that lives as long as the plugin, or the disposal of the panel that opened it.
 *
 * Not thread-safe. Drive one session from one thread; open a second session for a second thread.
 */
interface InterpretSession : Disposable {

    /**
     * What went wrong so far without stopping the run, oldest first: a statement skipped because it could not
     * be interpreted, an operation the sandbox blocked. Read it after a call and show what it says, the way the
     * built-in previews show their problem chip. A session that ran cleanly reports nothing.
     *
     * Deduplicated and bounded, so an interpreted loop cannot grow it without limit.
     */
    val problems: List<InterpretProblem>

    /** Drop the accumulated [problems], so the next call's are the only ones reported. */
    fun clearProblems()
}

/**
 * A session over interpreted **source** ([CodeInterpreter.openSource]).
 *
 * Arguments and return values are ordinary JVM values: pass a real `String`, `Int`, or an object of a class
 * the host or the plugin loaded, and get one back. An interpreted object comes back as an [InterpretedObject],
 * which is the handle for calling into it.
 */
interface SourceSession : InterpretSession {

    /**
     * Call a top-level function of the program.
     *
     * [entry] is the function's name, or `name/arity` to pick between overloads. Where the program was
     * lowered for a function, [LoweredProgram.entry] is already in that form and is the usual thing to pass.
     *
     * @throws InterpretException when the function is not in the program, or the call fails.
     */
    fun call(entry: String, args: List<Any?> = emptyList()): Any?

    /**
     * Construct one of the program's source classes ([LoweredProgram.types] lists them). [typeFqn] may be the
     * fully-qualified or the simple name.
     *
     * @throws InterpretException when the type is not in the program, or construction fails.
     */
    fun instantiate(typeFqn: String, args: List<Any?> = emptyList()): InterpretedObject
}

/**
 * A session over compiled classes on the bytecode VM ([CodeInterpreter.openBytecode]).
 *
 * The VM reads `.class` bytes off the configured classpath and executes them; nothing is dexed and no class
 * loader is given the user's code. Anything the VM's policy does not claim is bridged to real code, so a call
 * into interpreted code can call out into the framework the plugin bundles and back again.
 */
interface BytecodeSession : InterpretSession {

    /**
     * Call a static method of [classFqn]. The overload is chosen by name and argument count; pass [descriptor]
     * (a JVM method descriptor such as `(I)Ljava/lang/String;`) to name one exactly.
     *
     * @throws InterpretException when the class is not interpretable, no such method exists, or the call fails.
     */
    fun callStatic(
        classFqn: String,
        method: String,
        args: List<Any?> = emptyList(),
        descriptor: String? = null,
    ): Any?

    /**
     * Construct an instance of [classFqn], choosing the constructor by argument count unless [descriptor]
     * names one.
     *
     * @throws InterpretException when the class is not interpretable, no such constructor exists, or
     * construction fails.
     */
    fun construct(
        classFqn: String,
        args: List<Any?> = emptyList(),
        descriptor: String? = null,
    ): InterpretedObject

    /** Read a static field of [classFqn], running its class initializer first. */
    fun readStatic(classFqn: String, field: String): Any?

    /**
     * Ask the VM to abandon what it is running. The interpreter notices within a few thousand instructions, so
     * this stops even a tight loop, and the in-flight call fails with an [InterpretException]. Safe to call
     * from another thread; that is the point of it.
     */
    fun requestCancel()
}

/**
 * An object living inside a session: an instance of one of the user's classes.
 *
 * The interesting member is [proxy]. An interpreted object is not a real instance of anything a framework can
 * use, so handing the user's `Game` to a real engine means wrapping it in something that implements the
 * interface the engine expects and routes each call back into the interpreter. That is what [proxy] returns.
 */
interface InterpretedObject {

    /** Fully-qualified name of the class this is an instance of. */
    val typeFqn: String

    /**
     * Invoke a member function, choosing the overload by name and argument count. Virtual dispatch applies: an
     * override on this object's own class wins over the one it inherits.
     *
     * @throws InterpretException when there is no such member, or the call fails.
     */
    fun call(method: String, args: List<Any?> = emptyList()): Any?

    /** Read a property. @throws InterpretException when there is no such property. */
    fun get(property: String): Any?

    /** Write a property. @throws InterpretException when there is no such property. */
    fun set(property: String, value: Any?)

    /**
     * A real implementation of [iface] backed by this object: every call on it runs the corresponding member
     * in the interpreter. Hand it to code that has no idea an interpreter is involved.
     *
     * [iface] must be an interface, and it must be loadable by the plugin (its own bundled framework, or the
     * IDE's). A member the object does not implement fails when it is called, not here, because an interface
     * with a default method the user did not override is perfectly usable.
     *
     * @throws InterpretException when [iface] is not an interface.
     */
    fun <T : Any> proxy(iface: Class<T>): T

    /**
     * This object as the interpreter holds it: pass it back as an argument to another call, and nothing else.
     *
     * Opaque on purpose. For a source session it is the interpreter's own representation, for a bytecode
     * session the VM's peer, and neither is API. Reflecting on it will work until it does not.
     */
    val raw: Any
}

/**
 * Interpretation failed: the entry point was missing, an argument did not fit, the sandbox refused an
 * operation in strict mode, a recursion or time bound tripped, or the user's code threw.
 *
 * [cause] is the interpreter's own failure where there was one, so a plugin that wants the underlying reason
 * can read it, while [message] is already phrased for display.
 */
class InterpretException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
