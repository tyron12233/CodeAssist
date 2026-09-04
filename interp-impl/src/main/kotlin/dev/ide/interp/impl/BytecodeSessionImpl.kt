package dev.ide.interp.impl

import dev.ide.interp.api.BytecodeConfig
import dev.ide.interp.api.BytecodeSession
import dev.ide.interp.api.InterpretException
import dev.ide.interp.api.InterpretProblem
import dev.ide.interp.api.InterpretedObject
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.interpretedClassNameOf
import dev.ide.jvm.interpretedConstructors
import dev.ide.jvm.interpretedFieldValue
import dev.ide.jvm.interpretedMethods
import dev.ide.jvm.interpretedMethodsOf
import dev.ide.jvm.interpretedStaticValue
import dev.ide.jvm.setInterpretedFieldValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * A [BytecodeSession] over `:jvm-interp`'s VM.
 *
 * The VM reads `.class` bytes off the configured classpath and executes them, so nothing is dexed and no class
 * loader is handed the user's code. What the policy does not claim is bridged to real code through a
 * [ReflectiveBridge] over the caller's loader, which is how an interpreted class calls into the framework a
 * plugin bundles and gets called back.
 *
 * Values crossing in and out use real conventions: pass a `Boolean`, get a `Boolean`. An interpreted instance
 * crossing out is its peer, and for a class that implements only interfaces the peer already IS a real
 * implementation of them, so a plugin can often hand it straight to the framework without going through
 * [InterpretedObject.proxy].
 *
 * The session owns the jars it opened, which is why disposing it matters: it closes them and asks the VM to
 * abandon whatever is still running.
 */
internal class BytecodeSessionImpl(
    config: BytecodeConfig,
    peerFactory: PeerFactory?,
) : BytecodeSession {

    private val jars = ArrayList<JarFile>()

    private val vm = Vm(
        classpathSource(config.classpath, jars),
        policyFor(config),
        ReflectiveBridge(loader = config.libraryLoader ?: javaClass.classLoader),
        peerFactory ?: AsmPeerFactory(),
        config.threadStackSize,
    )

    /**
     * A bytecode session's problem list stays empty, and that is a statement rather than an omission: the VM's
     * boundary is its [dev.ide.jvm.NativeBridge], not the source interpreter's hook seam, so there is nothing
     * per-call to report (see [BytecodeConfig]). Present so both session kinds answer the same question the
     * same way.
     */
    override val problems: List<InterpretProblem> get() = emptyList()

    override fun clearProblems() = Unit

    private var closed = false

    override fun callStatic(
        classFqn: String,
        method: String,
        args: List<Any?>,
        descriptor: String?,
    ): Any? {
        checkOpen()
        val target = vm.interpretedMethods(classFqn)
            .select(classFqn, method, args.size, descriptor) { it.isStatic }
        return guarded { target.invoke(receiver = null, args = args) }
    }

    override fun construct(classFqn: String, args: List<Any?>, descriptor: String?): InterpretedObject {
        checkOpen()
        val ctor = vm.interpretedConstructors(classFqn)
            .select(classFqn, "<init>", args.size, descriptor) { true }
        val peer = guarded { ctor.invoke(receiver = null, args = args) }
            ?: throw InterpretException("constructing $classFqn produced no instance")
        return VmInstance(vm, peer, classFqn)
    }

    override fun readStatic(classFqn: String, field: String): Any? {
        checkOpen()
        return guarded { vm.interpretedStaticValue(classFqn, field) }
    }

    override fun requestCancel() = vm.requestCancel()

    override fun dispose() {
        if (closed) return
        closed = true
        // Abandon anything still running rather than leaving an interpreted loop on a thread nobody owns.
        vm.requestCancel()
        jars.forEach { runCatching { it.close() } }
        jars.clear()
    }

    private fun checkOpen() {
        if (closed) throw InterpretException("this session is closed")
    }
}

/**
 * The VM's interpret/bridge boundary for [config].
 *
 * With no prefixes named this is the console run's policy: interpret anything on the classpath that is not
 * the platform or the standard library. [BytecodeConfig.interpretPrefixes] narrows it to the packages the
 * plugin means to interpret, which is both faster (nothing else is ever parsed) and safer (the framework is
 * definitely the real one). [BytecodeConfig.bridgePrefixes] wins over both, for carving a package out.
 */
private fun policyFor(config: BytecodeConfig): InterpretPolicy {
    val bridge = config.bridgePrefixes.map { it.internalPrefix() }
    val interpret = config.interpretPrefixes.map { it.internalPrefix() }
    if (bridge.isEmpty() && interpret.isEmpty()) return InterpretPolicy.DEFAULT
    return InterpretPolicy { name ->
        when {
            bridge.any { name.startsWith(it) } -> false
            interpret.isEmpty() -> InterpretPolicy.DEFAULT.interpret(name)
            else -> interpret.any { name.startsWith(it) }
        }
    }
}

/** A binary or internal name prefix as the VM spells it (`com.example.` -> `com/example/`). */
private fun String.internalPrefix(): String = replace('.', '/')

/**
 * Reads `.class` bytes off [classpath]: directories first (a module's own output), then jars. The opened jars
 * land in [jarsOut] so the session can close them.
 */
private fun classpathSource(classpath: List<Path>, jarsOut: MutableList<JarFile>): ClassBytesSource {
    val dirs = classpath.filter { Files.isDirectory(it) }
    val jars = classpath.filter { Files.isRegularFile(it) }
        .mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }
    jarsOut.addAll(jars)
    return ClassBytesSource { internalName ->
        val rel = "$internalName.class"
        dirs.firstNotNullOfOrNull { d ->
            d.resolve(rel).takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
        } ?: jars.firstNotNullOfOrNull { jar ->
            jar.getJarEntry(rel)?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
        }
    }
}

/**
 * The one member of this list matching [name] and [arity], or [descriptor] exactly.
 *
 * Choosing by argument count is what a plugin can reasonably supply, and it is unambiguous for the shapes a
 * framework entry point has; where it is not, the descriptor is the escape hatch. Ambiguity is reported rather
 * than guessed at, and every failure lists what was actually there instead of only saying no.
 */
private fun List<VmMethodView>.select(
    classFqn: String,
    name: String,
    arity: Int,
    descriptor: String?,
    extra: (VmMethodView) -> Boolean,
): VmMethodView {
    if (isEmpty()) {
        throw InterpretException(
            "$classFqn is not interpretable: its class bytes are not on the session's classpath, or the " +
                "session's policy bridges it instead of interpreting it"
        )
    }
    val named = filter { it.name == name && extra(it) }
    if (named.isEmpty()) {
        throw InterpretException(
            "no $name on $classFqn (it has ${map { it.name }.distinct().sorted().joinToString()})"
        )
    }
    if (descriptor != null) {
        return named.firstOrNull { it.descriptor() == descriptor }
            ?: throw InterpretException(
                "no $classFqn.$name$descriptor (it has ${named.joinToString { it.descriptor() }})"
            )
    }
    val byArity = named.filter { it.paramDescriptors.size == arity }
    return when (byArity.size) {
        1 -> byArity.single()
        0 -> throw InterpretException(
            "no $classFqn.$name taking $arity argument(s) (it has ${named.joinToString { it.descriptor() }})"
        )
        else -> throw InterpretException(
            "$classFqn.$name has ${byArity.size} overloads taking $arity argument(s) " +
                "(${byArity.joinToString { it.descriptor() }}); pass a descriptor to choose"
        )
    }
}

private fun VmMethodView.descriptor(): String =
    "(" + paramDescriptors.joinToString("") + ")" + returnDescriptor

/** An [InterpretedObject] backed by a VM peer. */
internal class VmInstance(
    private val vm: Vm,
    private val peer: Any,
    declaredFqn: String,
) : InterpretedObject {

    override val typeFqn: String = interpretedClassNameOf(peer) ?: declaredFqn

    override val raw: Any get() = peer

    override fun call(method: String, args: List<Any?>): Any? {
        val target = vm.interpretedMethodsOf(peer)
            .select(typeFqn, method, args.size, descriptor = null) { !it.isStatic && !it.isAbstract }
        return guarded { target.invoke(peer, args) }
    }

    override fun get(property: String): Any? = guarded { vm.interpretedFieldValue(peer, property) }

    override fun set(property: String, value: Any?) {
        guarded { vm.setInterpretedFieldValue(peer, property, value) }
    }

    /** The peer may already BE an implementation of [iface] (a class implementing only interfaces is realized
     *  as a proxy of exactly those), in which case there is nothing to wrap. */
    override fun <T : Any> proxy(iface: Class<T>): T =
        if (iface.isInstance(peer)) iface.cast(peer)
        else interpretedProxy(iface) { name, args -> call(name, args) }

    override fun toString(): String = "interpreted $typeFqn"
}
