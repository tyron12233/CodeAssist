package dev.ide.jvm

/**
 * Runs a Kotlin **library reified inline function** (`filterIsInstance<R>`, `filterIsInstanceTo<R, C>`, and any
 * other `inline fun <reified R>` in a compiled facade) that cannot be dispatched reflectively — its JVM method
 * body is a `reifiedOperationMarker` stub that throws when called directly, because the real type only exists
 * after the compiler inlines it at the call site.
 *
 * This reproduces the compiler's reification transform at runtime: it INTERPRETS the function's bytecode on a
 * dedicated [Vm] that (a) treats each `reifiedOperationMarker` as an instruction to substitute the concrete
 * type argument into the following `INSTANCEOF`/`CHECKCAST`/`ANEWARRAY` (see [Interpreter]), and (b) bridges
 * everything the body touches (the receiver, `java.util` collections, iterators) to the real runtime. One
 * mechanism therefore covers every library reified inline, rather than a hand-written intrinsic per function.
 *
 * The [Vm]'s policy interprets only Kotlin **file facades** (classes whose simple name ends with `Kt`, where
 * top-level and extension functions live, plus their `@JvmMultifileClass` parts) and bridges all other
 * classes, so a reified facade function runs interpreted while its body's calls into `kotlin`/`java` stay real.
 */
class ReifiedInlineExecutor(
    /** Extra class bytes (a project's library jars) tried before the host classpath — for a reified inline that
     *  lives in a dependency rather than the standard library. */
    extraSource: ClassBytesSource? = null,
    private val loader: ClassLoader = ReifiedInlineExecutor::class.java.classLoader,
    peerFactory: PeerFactory = AsmPeerFactory(),
) {
    private val classpath = ClassBytesSource.fromClasspath(loader)

    private val vm = Vm(
        source = ClassBytesSource { name -> extraSource?.bytesFor(name) ?: classpath.bytesFor(name) },
        policy = InterpretPolicy { name -> isKotlinFacade(name) },
        peerFactory = peerFactory,
    )

    /** A Kotlin file facade or one of its multifile parts (`CollectionsKt`, `CollectionsKt___CollectionsKt`) —
     *  the only classes this executor interprets. Everything else is bridged to the real runtime. */
    private fun isKotlinFacade(internalName: String): Boolean =
        internalName.substringAfterLast('/').substringBefore('$').endsWith("Kt")

    /**
     * Invoke reified inline [name] on facade [ownerFqn], applying [reifiedTypes] (type-parameter name → the JVM
     * internal name of the concrete type argument) to the body's reified operations. [args] is the full JVM
     * argument list the static method expects (for an extension function the receiver is already [args]`[0]`).
     *
     * Returns a [Box] holding the result (which may legitimately be null), or null when this executor cannot run
     * it: the facade is not on the classpath, no matching method exists, or the body uses a reified operation
     * kind that is not modeled (a [VmUnsupportedException] from the interpreter). A genuine error thrown by the
     * function itself propagates.
     */
    fun invoke(ownerFqn: String, name: String, reifiedTypes: Map<String, String>, args: List<Any?>): Box? {
        val view = methodsFor(ownerFqn).firstOrNull {
            it.isStatic && !it.isAbstract && nameMatches(it.name, name) && it.paramDescriptors.size == args.size
        } ?: return null
        return try {
            Box(vm.withReifiedTypes(reifiedTypes) { view.invoke(null, args) })
        } catch (e: VmUnsupportedException) {
            null // an unmodeled reified kind / unbound type → let the caller fall back to its honest boundary
        }
    }

    /** The interpreted static methods of [ownerFqn], plus those of its conventional `@JvmMultifileClass` part
     *  (`<Facade>___<Facade>`) when the facade itself only delegates. */
    private fun methodsFor(ownerFqn: String): List<VmMethodView> {
        val facade = vm.interpretedMethods(ownerFqn)
        val simple = ownerFqn.substringAfterLast('.')
        val part = vm.interpretedMethods(ownerFqn + "___" + simple)
        return facade + part
    }

    /** Whether [jvmName] is Kotlin [kotlinName], allowing the inline value-class mangling `name-<hash>`. */
    private fun nameMatches(jvmName: String, kotlinName: String): Boolean =
        jvmName == kotlinName || (jvmName.startsWith("$kotlinName-") && '$' !in jvmName)

    /** A result box so a legitimately-null return is distinct from "could not run" (a null [Box]). */
    class Box(val value: Any?)
}
