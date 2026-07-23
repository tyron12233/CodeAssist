package dev.ide.interp

/** A value box so a legitimately-null property read is distinguishable from "not handled" (null box). */
class LibraryValue(val value: Any?)

/**
 * Executes dependency code the host's class loaders cannot load — classes that exist only as project-library
 * class bytes (a downloaded Maven jar / AAR). The preview provides a bytecode-VM implementation so dependency
 * code runs without ever being loaded into the runtime's class loader; with no executor the interpreter's
 * existing honest "cannot load" boundaries stand.
 *
 * Values use the interpreter's conventions (plain real values). Arguments may contain [OmittedArg] (a
 * named-argument call omitting a defaulted parameter — the executor routes through the Kotlin `$default`
 * synthetic) and [InterpretedLambda] (the executor proxies it into the functional interface the target
 * parameter expects). Method names are Kotlin names; the executor resolves JVM name mangling.
 */
interface LibraryExecutor {
    /** Whether [fqn] (a binary class name) is executable here. */
    fun hasClass(fqn: String): Boolean

    /** Whether [value] is an instance owned by this executor (returned from an earlier call). */
    fun ownsInstance(value: Any?): Boolean

    /** Invoke static [name] on [ownerFqn] (top-level/extension facades: any receiver is already the leading
     *  argument, and [leadingReceivers] counts those — they are not numbered in a Kotlin `$default` mask).
     *  Throws [InterpreterException] when nothing fits. */
    fun invokeStatic(ownerFqn: String, name: String, args: List<Any?>, leadingReceivers: Int = 0): Any?

    /** Invoke instance method [name] on [receiver] (an instance this executor owns). [leadingReceivers] counts
     *  extra receivers at the head of [args] (a member extension's extension receiver). */
    fun invokeInstance(receiver: Any, name: String, args: List<Any?>, leadingReceivers: Int = 0): Any?

    /** Construct an [ownerFqn] instance. */
    fun construct(ownerFqn: String, args: List<Any?>): Any?

    /** The `INSTANCE`/companion singleton of [ownerFqn], or null when it has none. */
    fun objectInstance(ownerFqn: String): Any?

    /** Read property [name] of [receiver] (an instance this executor owns): the Kotlin getter, a same-named
     *  method, or a nested `object`. Null when none exists (the caller keeps its own fallbacks). */
    fun propertyOrNull(receiver: Any, name: String): LibraryValue?

    /** Write property [name] of [receiver]; false when no setter exists. */
    fun writeProperty(receiver: Any, name: String, value: Any?): Boolean

    /**
     * Run a library **reified inline** function ([name] on [ownerFqn]) that cannot be dispatched reflectively —
     * its compiled JVM body is a reification-marker stub that throws when called directly, because the concrete
     * type only materializes when the compiler inlines it at the call site. The executor reproduces that inlining
     * by interpreting the body with [reifiedTypes] (each reified type-parameter name → the JVM internal name,
     * `java/lang/String`, of its concrete type argument) applied to the body's `is`/`as`/array operations.
     *
     * [args] is the full JVM argument list the static function expects — for an extension function the receiver
     * is already `args[0]`. Returns a [LibraryValue] (a null result is boxed) or null when the executor can't run
     * it (the class bytes aren't available, no such method, an unmodeled reified operation, or an unresolvable
     * type), so the caller keeps its honest boundary. The default declines, so an executor without a bytecode VM
     * behind it is unaffected.
     */
    fun invokeReifiedInline(ownerFqn: String, name: String, reifiedTypes: Map<String, String>, args: List<Any?>): LibraryValue? = null
}
