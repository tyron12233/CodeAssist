package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
import dev.ide.interp.InterpreterException
import dev.ide.interp.LibraryExecutor
import dev.ide.interp.LibraryValue
import dev.ide.interp.OmittedArg
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.hasInterpretedClass
import dev.ide.jvm.interpretedClassName
import dev.ide.jvm.interpretedConstructors
import dev.ide.jvm.interpretedMethods
import dev.ide.jvm.interpretedMethodsOf
import dev.ide.jvm.interpretedStaticFields
import dev.ide.jvm.interpretedStaticValue
import dev.ide.jvm.isInterpretedInstanceOf
import dev.ide.jvm.isVmPeer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * A [LibraryExecutor] backed by the `:jvm-interp` bytecode VM: dependency classes that exist only in the
 * project's library jars run INTERPRETED from their class bytes, while everything the host already bundles
 * (the Compose runtime/UI, the platform, the standard library) is bridged to the real classes. On device this
 * replaces the preview's `DexClassLoader` over downloaded jars — dependency code executes without ever being
 * loaded by ART.
 *
 * The interpreter resolves callees by Kotlin name and runtime argument shape, so this executor carries the
 * Kotlin-calling-convention knowledge over descriptors: JVM name mangling (`name-<hash>` for inline value
 * classes), the `$default` synthetics for omitted defaulted parameters, varargs, and functional-interface
 * proxies for interpreted lambdas.
 */
class VmLibraryExecutor(
    jars: List<Path> = emptyList(),
    peerFactory: PeerFactory = AsmPeerFactory(),
    private val hostLoader: ClassLoader = VmLibraryExecutor::class.java.classLoader,
    /** Test seam: overrides "the host can load this binary name" (a host-loadable class is bridged real). */
    private val hostLoadable: ((String) -> Boolean)? = null,
    /** Test seam: overrides where class bytes come from (default: the [jars]). */
    source: ClassBytesSource? = null,
) : LibraryExecutor, AutoCloseable {

    /** When set (the preview dispatcher wires it to its partial-render channel), a failure inside an
     *  interpreted lambda invoked from VM-executed or real code is REPORTED here and the call degrades to the
     *  return type's zero value, instead of propagating into framework code that would crash the host. Null
     *  (other hosts) keeps failures loud. */
    @Volatile
    var lambdaErrorSink: ((Throwable) -> Unit)? = null

    // One open JarFile per library jar, held for the executor's lifetime so a render doesn't reopen the jar per
    // class read (a Compose render touches many library classes). A JarFile IS a java.util.zip.ZipFile, so each
    // holds a file descriptor + a native zip handle that [close] releases. The host MUST close a discarded
    // executor (on file switch / when the preview leaves composition) — otherwise ART's CloseGuard fires
    // "A resource failed to call ZipFile.close" once per abandoned handle when it is finalized.
    private val jarFiles = jars.filter { Files.isRegularFile(it) }
        .mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }

    private val loadable = ConcurrentHashMap<String, Boolean>()
    private class ClassHolder(val cls: Class<*>?)
    private val hostClasses = ConcurrentHashMap<String, ClassHolder>()

    private fun isHostLoadable(binaryName: String): Boolean = loadable.getOrPut(binaryName) {
        hostLoadable?.invoke(binaryName)
            ?: (runCatching { Class.forName(binaryName, false, hostLoader) }.getOrNull() != null)
    }

    private val vm = Vm(
        source = source ?: ClassBytesSource { internalName ->
            jarFiles.firstNotNullOfOrNull { jar ->
                jar.getJarEntry("$internalName.class")?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
            }
        },
        policy = InterpretPolicy { internalName -> !isHostLoadable(internalName.replace('/', '.')) },
        peerFactory = peerFactory,
    )

    override fun hasClass(fqn: String): Boolean = vm.hasInterpretedClass(fqn)

    override fun ownsInstance(value: Any?): Boolean = isVmPeer(value)

    /** Release the open library-jar handles. Idempotent; the executor is unusable afterward, so callers close it
     *  only when discarding it (the preview host does this on file switch / when it leaves composition). */
    override fun close() {
        jarFiles.forEach { runCatching { it.close() } }
    }

    override fun invokeStatic(ownerFqn: String, name: String, args: List<Any?>, leadingReceivers: Int): Any? =
        call(vm.interpretedMethods(ownerFqn), name, wantStatic = true, receiver = null, args, leadingReceivers)?.value
            ?: throw InterpreterException("no static `$name`(${args.size}) on interpreted `$ownerFqn`")

    override fun invokeInstance(receiver: Any, name: String, args: List<Any?>, leadingReceivers: Int): Any? {
        call(vm.interpretedMethodsOf(receiver), name, wantStatic = false, receiver, args, leadingReceivers)
            ?.let { return it.value }
        // A nested `object` reached through member syntax (`Icons.AutoMirrored`): the class
        // `<Receiver>$<name>` with its own singleton, not a method on the receiver.
        if (args.isEmpty()) {
            vm.interpretedClassName(receiver)?.let { recvFqn ->
                objectInstance("$recvFqn\$$name")?.let { return it }
            }
        }
        throw InterpreterException("no method `$name`(${args.size}) on interpreted `${vm.interpretedClassName(receiver)}`")
    }

    override fun construct(ownerFqn: String, args: List<Any?>): Any? {
        val ctors = vm.interpretedConstructors(ownerFqn)
        val real = ctors.filterNot { isDefaultSyntheticCtor(it) }
        if (args.none { it === OmittedArg }) {
            real.firstOrNull { it.paramDescriptors.size == args.size && fitsAll(it.paramDescriptors, args) }
                ?.let { return it.invoke(null, bindArgs(it.paramDescriptors, args)) }
            real.firstOrNull { varargFits(it, args) }
                ?.let { return it.invoke(null, bindVarargs(it, args)) }
        }
        for (d in ctors.filter { isDefaultSyntheticCtor(it) }) {
            invokeDefault(d, args, maskShift = 0)?.let { return it.value }
        }
        if (args.none { it === OmittedArg }) {
            real.firstOrNull { it.paramDescriptors.size == args.size }
                ?.let { return it.invoke(null, bindArgs(it.paramDescriptors, args)) }
        }
        throw InterpreterException("no constructor `$ownerFqn`(${args.size}) in the library executor")
    }

    override fun objectInstance(ownerFqn: String): Any? {
        if (!vm.hasInterpretedClass(ownerFqn)) return null
        val fields = vm.interpretedStaticFields(ownerFqn)
        if ("INSTANCE" in fields) return vm.interpretedStaticValue(ownerFqn, "INSTANCE")
        // A companion is a static field whose type is the nested `<Owner>$<FieldName>` class — `Companion` by
        // default, its own name when named.
        val internalName = ownerFqn.replace('.', '/')
        val companion = fields.entries.firstOrNull { (n, d) -> d == "L$internalName\$$n;" }
        return companion?.let { vm.interpretedStaticValue(ownerFqn, it.key) }
    }

    override fun propertyOrNull(receiver: Any, name: String): LibraryValue? {
        val methods = vm.interpretedMethodsOf(receiver)
        call(methods, getterName(name), wantStatic = false, receiver, emptyList(), 0)?.let { return LibraryValue(it.value) }
        call(methods, name, wantStatic = false, receiver, emptyList(), 0)?.let { return LibraryValue(it.value) }
        vm.interpretedClassName(receiver)?.let { recvFqn ->
            objectInstance("$recvFqn\$$name")?.let { return LibraryValue(it) }
        }
        return null
    }

    override fun writeProperty(receiver: Any, name: String, value: Any?): Boolean {
        val methods = vm.interpretedMethodsOf(receiver)
        val setter = "set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val r = call(methods, setter, wantStatic = false, receiver, listOf(value), 0)
            ?: call(methods, name, wantStatic = false, receiver, listOf(value), 0)
        return r != null
    }

    // ---- composables -------------------------------------------------------------------------------

    /** Whether interpreted [ownerFqn] declares a transformed composable for Kotlin [method] — a method with a
     *  `Composer` parameter. The VM-side ground truth, mirroring `ComposableAbi.isComposableCall`. */
    fun isComposableCallable(ownerFqn: String, method: String): Boolean =
        vm.interpretedMethods(ownerFqn).any { nameMatches(it.name, method) && composerIndex(it) >= 0 }

    /**
     * Invoke a transformed library composable INTERPRETED in the VM, threading [composer] plus the trailing
     * `$changed`/`$default` ints — `ComposableAbi.call`'s contract carried over descriptors (see its
     * documentation for the binding rules; the parameters here mirror it one to one). The interpreted body
     * drives the REAL runtime through the bridge: slot-table calls dispatch on the live Composer, and the
     * interpreted restart lambda crosses out as a proxy the real Recomposer re-invokes on invalidation.
     */
    fun callComposable(
        ownerFqn: String,
        method: String,
        originalArgs: List<Any?>,
        composer: Any,
        declaredParamCount: Int = originalArgs.size,
        lambdaProxy: (InterpretedLambda, Class<*>) -> Any = { l, _ -> l },
        receiver: Any? = null,
        receiverCount: Int = 0,
        argsInDeclarationOrder: Boolean = false,
        lastArgIsTrailingLambda: Boolean = originalArgs.lastOrNull() is InterpretedLambda,
    ): Any? {
        val trailingLambda = !argsInDeclarationOrder && lastArgIsTrailingLambda && originalArgs.none { it === OmittedArg }
        val view = transformedView(ownerFqn, method, originalArgs, declaredParamCount, trailingLambda, wantStatic = receiver == null)
            ?: throw InterpreterException("no interpreted composable `$method`(${originalArgs.size}) on `$ownerFqn`")
        val params = view.paramDescriptors
        val n = composerIndex(view)
        val trailingInts = params.size - n - 1
        val changedInts = maxOf(1, (n + 9) / 10)
        val hasDefaults = trailingInts > changedInts

        val k = originalArgs.size
        val slots = arrayOfNulls<Any?>(n)
        val provided = BooleanArray(n)
        for (i in 0 until k) {
            val a = originalArgs[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n) continue
            val value = if (a is InterpretedLambda) {
                val iface = hostClass(params[slot])
                    ?: throw InterpreterException("lambda parameter type `${params[slot]}` is not loadable")
                lambdaProxy(a, iface)
            } else a
            // A supplied value that can't fit its slot is dropped to its default when one exists (the same
            // partial-render fallback ComposableAbi applies); with no defaults it binds anyway so a genuine
            // mismatch reports honestly.
            if (a is InterpretedLambda || !hasDefaults || fits(value, params[slot])) {
                slots[slot] = value
                provided[slot] = true
            }
        }
        for (i in 0 until n) if (!provided[i]) slots[i] = zeroValue(params[i])

        val args = ArrayList<Any?>(params.size)
        args.addAll(slots)
        args.add(composer)
        val valueParamCount = n - receiverCount
        if (provided.all { it }) {
            repeat(trailingInts) { args.add(0) }
        } else {
            val defaultIntCount = (valueParamCount + BITS_PER_DEFAULT_INT - 1) / BITS_PER_DEFAULT_INT
            val changedIntCount = trailingInts - defaultIntCount
            if (changedIntCount < 0) throw InterpreterException("unexpected composable ABI for `$method`: $trailingInts trailing ints, n=$n")
            repeat(changedIntCount) { args.add(0) }
            for (intIdx in 0 until defaultIntCount) {
                var mask = 0
                for (i in receiverCount until n) {
                    val vp = i - receiverCount
                    if (!provided[i] && vp / BITS_PER_DEFAULT_INT == intIdx) mask = mask or (1 shl (vp % BITS_PER_DEFAULT_INT))
                }
                args.add(mask)
            }
        }
        return view.invoke(receiver, args)
    }

    /** Read a `@Composable` property getter on an instance this executor owns, threading [composer]: the
     *  transformed getter is an instance method `get<Name>(Composer, int $changed)` — zero value parameters
     *  before the Composer (`ComposableAbi.readComposableProperty`'s contract over descriptors). Null when
     *  the receiver's interpreted class has no composer-taking getter for [name] (a plain property). */
    fun readComposableProperty(receiver: Any, name: String, composer: Any): LibraryValue? {
        val getter = getterName(name)
        val view = vm.interpretedMethodsOf(receiver).firstOrNull { v ->
            !v.isStatic && !v.isAbstract && nameMatches(v.name, getter) && composerIndex(v) == 0
        } ?: return null
        val args = ArrayList<Any?>(view.paramDescriptors.size)
        args.add(composer)
        repeat(view.paramDescriptors.size - 1) { args.add(0) }
        return LibraryValue(view.invoke(receiver, args))
    }

    /** Pick the transformed overload: prefer a candidate whose leading slots FIT the supplied args, then any
     *  with room for them, tie-broken toward the resolver's declared parameter count. */
    private fun transformedView(
        ownerFqn: String,
        method: String,
        args: List<Any?>,
        declaredParamCount: Int,
        trailingLambda: Boolean,
        wantStatic: Boolean,
    ): VmMethodView? {
        val candidates = vm.interpretedMethods(ownerFqn).filter {
            it.isStatic == wantStatic && !it.isAbstract && nameMatches(it.name, method) && composerIndex(it) >= 0
        }
        val fitting = candidates.filter { v ->
            val n = composerIndex(v)
            args.size <= n && argsFitSlots(v, args, trailingLambda, n)
        }
        val pool = fitting.ifEmpty { candidates.filter { composerIndex(it) >= args.size } }.ifEmpty { candidates }
        return pool.minByOrNull { kotlin.math.abs(composerIndex(it) - declaredParamCount) }
    }

    private fun argsFitSlots(v: VmMethodView, args: List<Any?>, trailingLambda: Boolean, n: Int): Boolean {
        for (i in args.indices) {
            val a = args[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == args.lastIndex) n - 1 else i
            if (slot !in 0 until n) return false
            if (a is InterpretedLambda) {
                if (hostClass(v.paramDescriptors[slot])?.isInterface == false) return false
            } else if (!fits(a, v.paramDescriptors[slot])) return false
        }
        return true
    }

    private fun composerIndex(v: VmMethodView): Int = v.paramDescriptors.indexOf(COMPOSER_DESC)

    // ---- resolution --------------------------------------------------------------------------------

    private class Res(val value: Any?)

    /** Resolve and invoke by Kotlin [kotlinName] and argument shape: exact-arity fit, then varargs, then the
     *  Kotlin `$default` synthetic (for [OmittedArg] holes or a call supplying fewer args than declared), then
     *  an arity-only last resort. Null when nothing fits (a legitimately-null RESULT comes back boxed). */
    private fun call(
        methods: List<VmMethodView>,
        kotlinName: String,
        wantStatic: Boolean,
        receiver: Any?,
        args: List<Any?>,
        leadingReceivers: Int,
    ): Res? {
        val named = methods.filter { it.isStatic == wantStatic && !it.isAbstract && nameMatches(it.name, kotlinName) }
        if (args.none { it === OmittedArg }) {
            named.firstOrNull { it.paramDescriptors.size == args.size && fitsAll(it.paramDescriptors, args) }
                ?.let { return Res(it.invoke(receiver, bindArgs(it.paramDescriptors, args))) }
            named.firstOrNull { varargFits(it, args) }
                ?.let { return Res(it.invoke(receiver, bindVarargs(it, args))) }
        }
        // `name$default` is STATIC even for an instance method, with the receiver as its first real parameter
        // (not numbered in the mask).
        val defaults = methods.filter { it.isStatic && isDefaultSynthetic(it.name, kotlinName) }
        if (defaults.isNotEmpty()) {
            val realArgs = if (wantStatic) args else listOf(receiver!!) + args
            val maskShift = leadingReceivers + if (wantStatic) 0 else 1
            for (d in defaults) invokeDefault(d, realArgs, maskShift)?.let { return it }
        }
        if (args.none { it === OmittedArg }) {
            named.firstOrNull { it.paramDescriptors.size == args.size }
                ?.let { return Res(it.invoke(receiver, bindArgs(it.paramDescriptors, args))) }
        }
        return null
    }

    /** Invoke a Kotlin default-arguments synthetic — `(realParams…, int mask, marker)` — filling omitted
     *  parameters with zero placeholders and set mask bits. [maskShift] = leading receiver params not numbered
     *  in the mask. Null when [view] doesn't fit the supplied args. */
    private fun invokeDefault(view: VmMethodView, realArgs: List<Any?>, maskShift: Int): Res? {
        val params = view.paramDescriptors
        if (params.size < 2 || params[params.size - 2] != "I") return null
        val marker = params.last()
        if (marker != "Ljava/lang/Object;" && marker != "Lkotlin/jvm/internal/DefaultConstructorMarker;") return null
        val n = params.size - 2
        val k = realArgs.size
        if (k > n) return null
        val ordered = realArgs.any { it === OmittedArg }
        val trailingLambda = !ordered && realArgs.lastOrNull() is InterpretedLambda
        for (i in 0 until k) {
            val a = realArgs[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n || !fits(a, params[slot])) return null
        }
        val slots = arrayOfNulls<Any?>(params.size)
        val provided = BooleanArray(n)
        var mask = 0
        for (i in 0 until k) {
            val a = realArgs[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            slots[slot] = bindOne(a, params[slot])
            provided[slot] = true
        }
        for (i in maskShift until n) if (!provided[i]) { slots[i] = zeroValue(params[i]); mask = mask or (1 shl (i - maskShift)) }
        for (i in 0 until maskShift) if (!provided[i]) slots[i] = zeroValue(params[i])
        slots[n] = mask
        slots[n + 1] = null
        return Res(view.invoke(null, slots.toList()))
    }

    private fun isDefaultSynthetic(jvmName: String, kotlinName: String): Boolean =
        jvmName == "$kotlinName\$default" || (jvmName.startsWith("$kotlinName-") && jvmName.endsWith("\$default"))

    private fun isDefaultSyntheticCtor(v: VmMethodView): Boolean =
        v.paramDescriptors.lastOrNull() == "Lkotlin/jvm/internal/DefaultConstructorMarker;"

    /** Kotlin-name match allowing the value-class JVM mangling `name-<hash>` (never containing `$`). */
    private fun nameMatches(jvmName: String, kotlinName: String): Boolean =
        jvmName == kotlinName || (jvmName.startsWith("$kotlinName-") && '$' !in jvmName)

    private fun getterName(property: String): String =
        "get" + property.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    // ---- fitting and binding -----------------------------------------------------------------------

    private fun fitsAll(params: List<String>, args: List<Any?>): Boolean =
        params.indices.all { fits(args[it], params[it]) }

    private fun fits(a: Any?, desc: String): Boolean = when {
        a === OmittedArg -> false
        desc.length == 1 -> when (desc[0]) {
            'Z' -> a is Boolean
            'C' -> a is Char
            'B', 'S', 'I' -> a is Int
            'J' -> a is Long
            'F' -> a is Float
            'D' -> a is Double
            else -> false
        }
        a == null -> true
        a is InterpretedLambda -> hostClass(desc)?.isInterface ?: true
        desc.startsWith("[") -> a.javaClass.isArray
        else -> refFits(a, desc)
    }

    private fun refFits(a: Any, desc: String): Boolean {
        val binary = desc.substring(1, desc.length - 1).replace('/', '.')
        if (binary == "java.lang.Object") return true
        if (isVmPeer(a) && vm.hasInterpretedClass(binary)) return vm.isInterpretedInstanceOf(a, binary)
        // A peer also IS its real supertypes; an unloadable parameter type is trusted (arity carried the fit).
        return hostClass(desc)?.isInstance(a) ?: true
    }

    private fun varargFits(view: VmMethodView, args: List<Any?>): Boolean {
        if (!view.isVarargs) return false
        val params = view.paramDescriptors
        val fixed = params.size - 1
        if (args.size < fixed) return false
        val componentDesc = params.last().removePrefix("[")
        return (0 until fixed).all { fits(args[it], params[it]) } &&
            (fixed until args.size).all { fits(args[it], componentDesc) }
    }

    private fun bindArgs(params: List<String>, args: List<Any?>): List<Any?> =
        args.mapIndexed { i, a -> bindOne(a, params[i]) }

    private fun bindOne(a: Any?, desc: String): Any? =
        if (a is InterpretedLambda) lambdaProxy(a, desc) else a

    private fun bindVarargs(view: VmMethodView, args: List<Any?>): List<Any?> {
        val params = view.paramDescriptors
        val fixed = params.size - 1
        val componentDesc = params.last().removePrefix("[")
        val fixedArgs = (0 until fixed).map { bindOne(args[it], params[it]) }
        val rest = args.drop(fixed).map { bindOne(it, componentDesc) }
        val componentClass = when {
            componentDesc.length == 1 -> primClass(componentDesc[0])
            else -> hostClass(componentDesc) ?: Any::class.java
        }
        val arr = java.lang.reflect.Array.newInstance(componentClass, rest.size)
        rest.forEachIndexed { i, v -> java.lang.reflect.Array.set(arr, i, v) }
        return fixedArgs + listOf(arr)
    }

    private fun zeroValue(desc: String): Any? = when (desc) {
        "Z" -> false
        "C" -> ' '
        "B", "S", "I" -> 0
        "J" -> 0L
        "F" -> 0f
        "D" -> 0.0
        else -> null
    }

    private companion object {
        const val COMPOSER_DESC = "Landroidx/compose/runtime/Composer;"
        const val BITS_PER_DEFAULT_INT = 31
    }

    private fun primClass(d: Char): Class<*> = when (d) {
        'Z' -> Boolean::class.javaPrimitiveType!!
        'C' -> Char::class.javaPrimitiveType!!
        'B' -> Byte::class.javaPrimitiveType!!
        'S' -> Short::class.javaPrimitiveType!!
        'I' -> Int::class.javaPrimitiveType!!
        'J' -> Long::class.javaPrimitiveType!!
        'F' -> Float::class.javaPrimitiveType!!
        'D' -> Double::class.javaPrimitiveType!!
        else -> error("bad primitive descriptor $d")
    }

    private fun hostClass(desc: String): Class<*>? {
        if (!desc.startsWith("L")) return null
        val binary = desc.substring(1, desc.length - 1).replace('/', '.')
        return hostClasses.getOrPut(binary) {
            ClassHolder(runCatching { Class.forName(binary, false, hostLoader) }.getOrNull())
        }.cls
    }

    /** Wrap an interpreted lambda in a real proxy of the functional interface a parameter expects, so the
     *  VM-interpreted callee (and any real code it hands the value to) can call it. */
    private fun lambdaProxy(lambda: InterpretedLambda, ifaceDesc: String): Any {
        val iface = hostClass(ifaceDesc)?.takeIf { it.isInterface }
            ?: throw InterpreterException("lambda parameter type `$ifaceDesc` is not a loadable interface")
        return Proxy.newProxyInstance(iface.classLoader ?: hostLoader, arrayOf(iface)) { proxy, method, cargs ->
            when (method.name) {
                "toString" -> lambda.toString()
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> cargs?.getOrNull(0) === proxy
                else -> {
                    val result = try {
                        lambda.invoke((cargs ?: emptyArray()).toList())
                    } catch (t: Throwable) {
                        val sink = lambdaErrorSink ?: throw t
                        sink(t)
                        zeroValue(primDesc(method.returnType))
                    }
                    if (method.returnType == Void.TYPE) null else result
                }
            }
        }
    }

    private fun primDesc(type: Class<*>): String = when (type) {
        Boolean::class.javaPrimitiveType -> "Z"
        Char::class.javaPrimitiveType -> "C"
        Byte::class.javaPrimitiveType -> "B"
        Short::class.javaPrimitiveType -> "S"
        Int::class.javaPrimitiveType -> "I"
        Long::class.javaPrimitiveType -> "J"
        Float::class.javaPrimitiveType -> "F"
        Double::class.javaPrimitiveType -> "D"
        else -> "L;"
    }
}
