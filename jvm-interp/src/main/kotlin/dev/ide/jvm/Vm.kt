package dev.ide.jvm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

/** Supplies a class's bytecode by internal name (`dev/ide/jvm/Foo`), or null when no such class is available. */
fun interface ClassBytesSource {
    fun bytesFor(internalName: String): ByteArray?

    companion object {
        /** Reads class files as resources from [loader] (by default, the caller's own classpath). */
        fun fromClasspath(loader: ClassLoader = ClassBytesSource::class.java.classLoader): ClassBytesSource =
            ClassBytesSource { name -> loader.getResourceAsStream("$name.class")?.use { it.readBytes() } }
    }
}

/** Decides which classes the interpreter runs; the rest are handled by the [NativeBridge]. */
fun interface InterpretPolicy {
    fun interpret(internalName: String): Boolean

    companion object {
        /** Interprets every class except the platform and standard-library namespaces, which are bridged. */
        val DEFAULT = InterpretPolicy { name ->
            BRIDGED_PREFIXES.none { name.startsWith(it) }
        }
        private val BRIDGED_PREFIXES = listOf(
            "java/", "javax/", "jdk/", "sun/", "com/sun/", "kotlin/", "kotlinx/",
            "android/", "androidx/", "com/android/", "dalvik/", "org/jetbrains/",
        )
    }
}

/**
 * A stack-based interpreter for JVM class-file bytecode. It parses classes from [source] that [policy] marks
 * interpretable, runs their methods instruction by instruction (see [Interpreter]), and routes every other
 * call through [bridge]. Interpreted code never reaches the host class loader; access to classes the policy
 * excludes is mediated by the bridge.
 *
 * The interpreter is single-threaded, and interpreted calls recurse on the host call stack, so deep
 * interpreted recursion is bounded by the host stack size.
 */
class Vm(
    private val source: ClassBytesSource = ClassBytesSource.fromClasspath(),
    private val policy: InterpretPolicy = InterpretPolicy.DEFAULT,
    internal val bridge: NativeBridge = ReflectiveBridge(),
    /** Produces the real peer objects that let platform code invoke interpreted overrides. */
    private val peerFactory: PeerFactory = AsmPeerFactory(),
) {
    private val classes = HashMap<String, VmClass?>()
    private val interpreter = Interpreter(this)

    /** Total bytecode instructions the interpreter has executed (diagnostics / throughput measurement). */
    val steps: Long get() = interpreter.steps

    /** Request cancellation of a running program from another thread: the interpreter loop observes it within a
     *  few thousand instructions and unwinds with a [VmInterruptedException]. A program blocked in a bridged
     *  call (reading stdin, sleeping) does not observe this — the host also interrupts the run thread to break
     *  such a blocking call. One-shot: a cancelled [Vm] should not be reused. */
    fun requestCancel() { interpreter.cancelRequested = true }
    private val loader: ClassLoader = Vm::class.java.classLoader
    private val peerDispatch = PeerDispatch { peer, vmObject, name, descriptor, args ->
        val obj = vmObject as VmObject
        // Link the peer to its interpreted instance on the first dispatch. A real superclass constructor can
        // call an overridden method while the peer is still being constructed (ImageView's constructor calls
        // setImageDrawable), and the override may hand `this` back to platform code — which must resolve to this
        // peer-under-construction, not a second, no-argument peer built before initPeer records this one.
        if (obj.peer == null) obj.peer = peer
        runInterpretedOverride(obj, name, descriptor, args)
    }

    /** The interpreted [VmClass] for [internalName], or null when it should be bridged (platform/absent). */
    internal fun resolve(internalName: String): VmClass? = classes.getOrPut(internalName) {
        if (!policy.interpret(internalName)) return@getOrPut null
        val bytes = source.bytesFor(internalName) ?: return@getOrPut null
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
        VmClass(cn.name, cn.superName, cn.interfaces ?: emptyList(), cn.access, cn.methods ?: emptyList(), cn.fields ?: emptyList())
    }

    // ---- public entry points ----------------------------------------------------------------------

    /** Invoke a static method and return its (marshalled) result. Args use the interpreter's value conventions.
     *  An interpreted exception that escapes the method surfaces as a real throwable (see [surfacing]). */
    fun invokeStatic(owner: String, name: String, descriptor: String, args: List<Any?> = emptyList()): Any? = surfacing {
        val cls = resolve(owner)
            ?: return@surfacing bridge.invokeStatic(owner, name, descriptor, args)
        ensureInitialized(cls)
        val m = findInHierarchy(cls, name, descriptor)?.second
            ?: throw VmUnsupportedException("no static method $owner.$name$descriptor")
        // A returned interpreted object is handed to the (real) caller as its peer.
        toReal(interpreter.execute(cls, m, receiver = null, args = args))
    }

    /** Construct an object of [owner]: an interpreted class runs its `<init>`, a bridged class is built by the
     *  [NativeBridge]. */
    fun construct(owner: String, descriptor: String, args: List<Any?> = emptyList()): Any = surfacing {
        val cls = resolve(owner)
            ?: return@surfacing bridgeConstruct(owner, descriptor, args)
                ?: throw VmUnsupportedException("construction of $owner returned no instance")
        ensureInitialized(cls)
        val obj = newInstance(cls)
        val ctor = cls.declaredMethod("<init>", descriptor)
            ?: throw VmUnsupportedException("no constructor $owner.<init>$descriptor")
        interpreter.execute(cls, ctor, receiver = obj, args = args)
        obj
    }

    /** Invoke through a [VmMethodView]: marshal real-convention arguments in by the declared parameter
     *  descriptors, run the method (a constructor allocates first), and marshal the result out by the return
     *  descriptor, converting an interpreted instance to its peer. */
    internal fun invokeView(
        declaring: VmClass,
        node: org.objectweb.asm.tree.MethodNode,
        receiver: Any?,
        args: List<Any?>,
        paramDescs: List<String>,
        returnDesc: String,
    ): Any? = surfacing {
        ensureInitialized(declaring)
        val vmArgs = args.mapIndexed { i, a -> Marshalling.realToVm(a, paramDescs[i]) }
        if (node.name == "<init>") {
            val obj = newInstance(declaring)
            interpreter.execute(declaring, node, obj, vmArgs)
            return@surfacing toReal(obj)
        }
        val vmReceiver = receiver?.let { Marshalling.realToVm(it) }
        val result = interpreter.execute(declaring, node, vmReceiver, vmArgs)
        Marshalling.vmToReal(toReal(result), returnDesc)
    }

    /** Run [body], turning an interpreted exception that reached the top of the call into a real throwable: a
     *  thrown real [Throwable] is rethrown as-is; a thrown interpreted [VmObject] is wrapped so callers still
     *  get a failure rather than an opaque internal signal. */
    private inline fun <T> surfacing(body: () -> T): T =
        try {
            body()
        } catch (ve: VmException) {
            when (val v = ve.value) {
                is Throwable -> throw v
                else -> throw RuntimeException("uninterpreted exception escaped: $v")
            }
        }

    // ---- peers (real objects for interpreted instances) -------------------------------------------

    /** Convert a value bound for platform code: an interpreted object becomes its peer, an interpreted array a
     *  real array, everything else passes through. */
    internal fun toReal(value: Any?): Any? = when (value) {
        is VmObject -> peerOf(value)
        is VmArray -> toRealArray(value)
        else -> value
    }

    /** A real Java array mirroring the interpreted array, reused across calls and refreshed from the current
     *  interpreted contents each time, so it can be synced back after a platform call ([syncArraysBack]). */
    private fun toRealArray(arr: VmArray): Any {
        val mirror = arr.realMirror
            ?: java.lang.reflect.Array.newInstance(realComponentType(arr.elementDescriptor), arr.length).also { arr.realMirror = it }
        for (i in 0 until arr.length) java.lang.reflect.Array.set(mirror, i, Marshalling.toRealArg(toReal(arr.data[i]), arr.elementDescriptor))
        return mirror
    }

    /** Copy each argument array's real mirror back into the interpreted array after a platform call, so an
     *  in-place mutation (sort, fill, arraycopy into it) is visible to interpreted code. Nested arrays recurse. */
    private fun syncArraysBack(vmArgs: List<Any?>) {
        vmArgs.forEach { if (it is VmArray) syncBack(it) }
    }

    private fun syncBack(arr: VmArray) {
        val mirror = arr.realMirror ?: return
        for (i in 0 until arr.length) {
            val element = arr.data[i]
            if (element is VmArray) syncBack(element) // the mirror element is this nested array's own mirror
            else arr.data[i] = Marshalling.realToVm(java.lang.reflect.Array.get(mirror, i), arr.elementDescriptor)
        }
    }

    // ---- bridge invocation (marshals interpreter values, then syncs argument arrays back) ----------

    internal fun bridgeStatic(owner: String, name: String, descriptor: String, vmArgs: List<Any?>): Any? {
        val real = vmArgs.map { toReal(it) }
        interceptReflection(null, owner, name, descriptor, real)?.let { return it.value }
        return bridge.invokeStatic(owner, name, descriptor, real).also { syncArraysBack(vmArgs) }
    }

    internal fun bridgeVirtual(receiver: Any, name: String, descriptor: String, vmArgs: List<Any?>): Any? {
        val real = vmArgs.map { toReal(it) }
        interceptReflection(receiver, receiver.javaClass.name.replace('.', '/'), name, descriptor, real)?.let { return it.value }
        return bridge.invokeVirtual(receiver, name, descriptor, real).also { syncArraysBack(vmArgs) }
    }

    /** Carries a reflection-interception result (the value may legitimately be null). */
    private class Reflected(val value: Any?)

    /**
     * Intercepts the reflection a class can perform on an INTERPRETED type — there is no real `Class` for such a
     * type, so the runtime substitutes a [PeerFactory.reflectionClass] and services these operations against the
     * interpreter. Returns a [Reflected] holder when handled, or null to let the call bridge normally. Covers the
     * `loadClass` / `Class.forName` / `asSubclass` / `Constructor.newInstance` chain a framework uses to
     * instantiate a class named by a resource (e.g. CoordinatorLayout's `app:layout_behavior`).
     */
    private fun interceptReflection(receiver: Any?, owner: String, name: String, descriptor: String, args: List<Any?>): Reflected? {
        // ClassLoader.loadClass("a.b.C") -> the reflection Class for an interpreted C (else null: a real class).
        if (receiver is ClassLoader && name == "loadClass" && descriptor == "(Ljava/lang/String;)Ljava/lang/Class;") {
            val internal = (args.getOrNull(0) as? String)?.replace('.', '/') ?: return null
            return classForInterpreted(internal)?.let { Reflected(it) }
        }
        // Class.forName("a.b.C") / forName("a.b.C", init, loader).
        if (owner == "java/lang/Class" && receiver == null && name == "forName") {
            val internal = (args.getOrNull(0) as? String)?.replace('.', '/') ?: return null
            return classForInterpreted(internal)?.let { Reflected(it) }
        }
        // Class.asSubclass(Base.class) on a reflection Class: verify against the interpreted hierarchy.
        if (receiver is Class<*> && name == "asSubclass") {
            val recvName = peerFactory.interpretedNameOf(receiver) ?: return null
            val target = args.getOrNull(0) as? Class<*> ?: return null
            val targetName = peerFactory.interpretedNameOf(target)
            val ok = if (targetName != null) resolve(recvName)?.let { isSubtype(it, targetName) } == true else true
            if (ok) return Reflected(receiver)
            throw ClassCastException("$recvName is not assignable to ${targetName ?: target.name}")
        }
        // Constructor.newInstance(args) on a reflection-class constructor: run the interpreted constructor.
        if (receiver is java.lang.reflect.Constructor<*> && name == "newInstance") {
            val internal = peerFactory.interpretedNameOf(receiver.declaringClass) ?: return null
            val ctorArgs = (args.getOrNull(0) as? Array<*>)?.toList() ?: emptyList()
            return Reflected(constructReflectively(internal, ctorArgs))
        }
        return null
    }

    /** The real [Class] that stands for interpreted type [internalName] in reflection (its stub constructors
     *  mirror the interpreted class's public constructors), or null when the type is not interpreted. */
    internal fun classForInterpreted(internalName: String): Class<*>? {
        val cls = resolve(internalName) ?: return null
        val ctors = cls.methods
            .filter { it.name == "<init>" && it.access and Opcodes.ACC_PUBLIC != 0 }
            .map { "(" + Descriptors.paramTypes(it.desc).joinToString("") { p -> realizeType(p) } + ")V" }
        return peerFactory.reflectionClass(internalName, ctors)
    }

    /** A parameter descriptor with any INTERPRETED reference type replaced by `Object` (no real class exists for
     *  it), so a reflection-class stub constructor uses only loadable parameter types. */
    private fun realizeType(t: String): String = when {
        t.startsWith("[") -> "[" + realizeType(t.substring(1))
        t.startsWith("L") -> if (resolve(t.substring(1, t.length - 1)) != null) "Ljava/lang/Object;" else t
        else -> t
    }

    /** Construct an interpreted instance of [internalName] for a reflective `newInstance`: pick the public
     *  constructor matching the argument count, marshal the real arguments in, run its `<init>`, and return the
     *  interpreted object (the interpreted caller uses it directly; it becomes a peer only if it later crosses to
     *  real code). */
    private fun constructReflectively(internalName: String, realArgs: List<Any?>): Any {
        val cls = resolve(internalName) ?: throw VmUnsupportedException("$internalName is not interpreted")
        ensureInitialized(cls)
        val ctor = cls.methods.firstOrNull {
            it.name == "<init>" && it.access and Opcodes.ACC_PUBLIC != 0 && Descriptors.paramTypes(it.desc).size == realArgs.size
        } ?: throw VmUnsupportedException("no ${realArgs.size}-argument constructor on $internalName")
        val params = Descriptors.paramTypes(ctor.desc)
        val vmArgs = realArgs.mapIndexed { i, a -> Marshalling.realToVm(a, params[i]) }
        val obj = newInstance(cls)
        interpreter.execute(cls, ctor, obj, vmArgs)
        return obj
    }

    internal fun bridgeConstruct(owner: String, descriptor: String, vmArgs: List<Any?>): Any? =
        bridge.construct(owner, descriptor, vmArgs.map { toReal(it) }).also { syncArraysBack(vmArgs) }

    /** The [Class] of an array component from its type descriptor. An INTERPRETED element type maps to its real
     *  supertype: the elements crossing the bridge are peers, which are instances of that supertype, not of the
     *  real class of the same name a host classpath may also carry (e.g. an interpreted enum's `$VALUES` handed
     *  to `enumEntries` — the peers extend `java.lang.Enum`). */
    internal fun realComponentType(descriptor: String): Class<*> = when (descriptor) {
        "I" -> Int::class.javaPrimitiveType!!
        "J" -> Long::class.javaPrimitiveType!!
        "F" -> Float::class.javaPrimitiveType!!
        "D" -> Double::class.javaPrimitiveType!!
        "Z" -> Boolean::class.javaPrimitiveType!!
        "B" -> Byte::class.javaPrimitiveType!!
        "C" -> Char::class.javaPrimitiveType!!
        "S" -> Short::class.javaPrimitiveType!!
        else -> if (descriptor.startsWith("[")) loadReal(descriptor) else {
            val internalName = descriptor.substring(1, descriptor.length - 1)
            val cls = resolve(internalName)
            if (cls != null) loadReal(realSuperName(cls)) else loadReal(internalName)
        }
    }

    /** Create the peer for [o] at the interpreted `super(...)` call into a real supertype, invoking that exact
     *  superclass constructor with the given (interpreter-representation) arguments. A trivial class (no real
     *  supertype behavior) gets no peer. */
    internal fun initPeer(o: VmObject, superDescriptor: String, superArgs: List<Any?>) {
        if (o.peer != null) return
        val spec = peerSpec(o.vmClass)
        if (spec.isTrivial) return
        val realArgs = Descriptors.paramTypes(superDescriptor).mapIndexed { i, d -> Marshalling.toRealArg(toReal(superArgs[i]), d) }
        // createPeer runs the real super constructor, which may dispatch an overridden method back into the
        // interpreter and link o.peer to the peer under construction (see peerDispatch). Assign only if that
        // did not already happen, so the two references stay the same object.
        val created = peerFactory.createPeer(o, spec, peerDispatch, superDescriptor, realArgs)
        if (o.peer == null) o.peer = created
    }

    /** The real peer for [o], created lazily with the real superclass's no-argument constructor when the object
     *  never reached [initPeer] (a class over `Object` handed to platform code). */
    internal fun peerOf(o: VmObject): Any =
        o.peer ?: peerFactory.createPeer(o, peerSpec(o.vmClass), peerDispatch, "()V", emptyList()).also { o.peer = it }

    /** Run the interpreted override [name]/[descriptor] on [o] when its peer method is called by platform code,
     *  marshalling the real arguments in and the result out. */
    private fun runInterpretedOverride(o: VmObject, name: String, descriptor: String, args: Array<Any?>): Any? = surfacing {
        val params = Descriptors.paramTypes(descriptor)
        val vmArgs = args.mapIndexed { i, a -> Marshalling.realToVm(a, params[i]) }
        val (declaring, method) = findInHierarchy(o.vmClass, name, descriptor)
            ?: throw VmUnsupportedException("no interpreted override $name$descriptor on ${o.vmClass.name}")
        val result = interpreter.execute(declaring, method, o, vmArgs)
        if (result is VmObject) peerOf(result) else Marshalling.vmToReal(result, Descriptors.returnType(descriptor))
    }

    /** The peer shape for [vmClass]: its nearest real superclass, the real interfaces it declares, the
     *  real-supertype methods it overrides, and the abstract supertype methods it leaves unimplemented. */
    private fun peerSpec(vmClass: VmClass): PeerSpec {
        val superClass = loadReal(realSuperName(vmClass))
        val interfaces = realInterfaceNames(vmClass).map { loadReal(it) }
        val candidates = overridableMethods(superClass, interfaces)
        val methods = ArrayList<PeerMethod>()
        val stubs = ArrayList<PeerMethod>()
        for ((_, candidate) in candidates) {
            when {
                declaresInChain(vmClass, candidate.method.name, candidate.method.descriptor) -> methods.add(candidate.method)
                candidate.abstract -> stubs.add(candidate.method)
            }
        }
        return PeerSpec(superClass, interfaces, methods, stubs, vmClass.name)
    }

    /** The first ancestor of [vmClass] that is not itself interpreted (a real superclass), or `Object`. */
    internal fun realSuperName(vmClass: VmClass): String {
        var c: VmClass? = vmClass
        while (c != null) {
            val sn = c.superName ?: return "java/lang/Object"
            val sc = resolve(sn) ?: return sn
            c = sc
        }
        return "java/lang/Object"
    }

    /** The real (non-interpreted) interfaces reachable anywhere in [vmClass]'s chain, including those an
     *  INTERPRETED interface extends (an interpreted `MonotonicFrameClock` extending the real
     *  `CoroutineContext.Element` makes every implementor an Element too). */
    private fun realInterfaceNames(vmClass: VmClass): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        var c: VmClass? = vmClass
        while (c != null) {
            queue.addAll(c.interfaces)
            c = c.superName?.let { resolve(it) }
        }
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) continue
            val ic = resolve(n)
            if (ic == null) out.add(n) else queue.addAll(ic.interfaces)
        }
        return out
    }

    /** Whether an interpreted class in [vmClass]'s chain declares instance field [name]. Distinguishes a field
     *  that lives on the VmObject from one inherited from a real supertype (which lives on the peer), regardless
     *  of the field-reference owner the compiler emitted. */
    internal fun vmDeclaresField(vmClass: VmClass, name: String): Boolean {
        var c: VmClass? = vmClass
        while (c != null) {
            if (name in c.instanceFieldDescs) return true
            c = c.superName?.let { resolve(it) }
        }
        return false
    }

    /** Whether the interpreted side provides an implementation the peer should forward to: a method in the
     *  class chain or an interpreted interface's default method (the frame-clock `key` case). */
    private fun declaresInChain(vmClass: VmClass, name: String, descriptor: String): Boolean =
        findInHierarchy(vmClass, name, descriptor) != null

    private fun loadReal(internalName: String): Class<*> =
        Class.forName(internalName.replace('/', '.'), false, loader)

    /** The real [Class] a `ldc` class-literal constant names. Only reference and array literals reach here (a
     *  primitive `.class` compiles to a static `TYPE` field read). An interpreted type is represented by its
     *  reflection class — the same object `loadClass`/`Class.forName` return for it and that
     *  [interceptReflection] recognises, so `X.class` and a reflectively-loaded `X` compare equal. */
    internal fun classLiteral(type: Type): Any = when {
        type.sort == Type.ARRAY -> Class.forName(type.descriptor.replace('/', '.'), false, loader)
        else -> classForInterpreted(type.internalName) ?: loadReal(type.internalName)
    }

    /** Whether an interpreted instance of [vmClass] is a [targetInternalName] by its REAL supertypes (the real
     *  superclass or a real interface, transitively). Complements the interpreted-hierarchy check in [isSubtype]
     *  so an `instanceof`/`checkcast` against a real interface reached through a bridged super resolves. */
    internal fun isRealInstance(vmClass: VmClass, targetInternalName: String): Boolean {
        val target = runCatching { loadReal(targetInternalName) }.getOrNull() ?: return false
        val realSupers = buildList {
            add(loadReal(realSuperName(vmClass)))
            realInterfaceNames(vmClass).forEach { runCatching { loadReal(it) }.getOrNull()?.let(::add) }
        }
        return realSupers.any { target.isAssignableFrom(it) }
    }

    /** A candidate supertype method a peer may override, paired with whether it is abstract. */
    private class Candidate(val method: PeerMethod, val abstract: Boolean)

    /** The overridable methods (public or protected, non-final, non-static) reachable through [superClass] and
     *  [interfaces], keyed by name+descriptor so the most-derived declaration of each is kept once. A method a
     *  superclass declares FINAL blocks the signature entirely — the same signature reachable through an
     *  interface (Map.size vs kotlin AbstractMap's final size) must not become an override. */
    private fun overridableMethods(superClass: Class<*>, interfaces: List<Class<*>>): Map<String, Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        val blocked = HashSet<String>()
        fun consider(m: java.lang.reflect.Method) {
            val mod = m.modifiers
            if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isPrivate(mod)) return
            if (!java.lang.reflect.Modifier.isPublic(mod) && !java.lang.reflect.Modifier.isProtected(mod)) return
            val descriptor = org.objectweb.asm.Type.getMethodDescriptor(m)
            val key = m.name + descriptor
            if (java.lang.reflect.Modifier.isFinal(mod)) { blocked.add(key); return }
            if (key in blocked) return
            val peerMethod = PeerMethod(m.name, descriptor, org.objectweb.asm.Type.getInternalName(m.declaringClass), m.declaringClass.isInterface)
            out.putIfAbsent(key, Candidate(peerMethod, java.lang.reflect.Modifier.isAbstract(mod)))
        }
        // The class chain is walked before the interfaces, so a final class method is recorded before the same
        // signature is seen abstract on an interface.
        var c: Class<*>? = superClass
        while (c != null) { c.declaredMethods.forEach(::consider); c = c.superclass }
        val seenIfaces = HashSet<Class<*>>()
        val queue = ArrayDeque(interfaces)
        while (queue.isNotEmpty()) {
            val iface = queue.removeFirst()
            if (!seenIfaces.add(iface)) continue
            iface.declaredMethods.forEach(::consider)
            queue.addAll(iface.interfaces)
        }
        return out
    }

    // ---- internals used by the interpreter --------------------------------------------------------

    /** A fresh [VmObject] with every instance field (this class + interpreted supers) defaulted. */
    internal fun newInstance(cls: VmClass): VmObject {
        val obj = VmObject(cls)
        var c: VmClass? = cls
        while (c != null) {
            c.instanceFieldDescs.forEach { (n, d) -> obj.fields.putIfAbsent(n, Descriptors.defaultValue(d)) }
            c = c.superName?.let { resolve(it) }
        }
        return obj
    }

    /** Run [cls]'s `<clinit>` once, lazily, superclass first, mirroring JVM class-initialization order. */
    internal fun ensureInitialized(cls: VmClass) {
        if (cls.initialized) return
        cls.initialized = true
        cls.superName?.let { resolve(it) }?.let { ensureInitialized(it) }
        cls.declaredMethod("<clinit>", "()V")?.let { interpreter.execute(cls, it, receiver = null, args = emptyList()) }
    }

    private class Resolved(val target: Pair<VmClass, org.objectweb.asm.tree.MethodNode>?)
    private val resolveCache = HashMap<VmClass, HashMap<String, Resolved>>()

    /** Resolve a method by walking [start]'s superclass chain (for static/special resolution and as the base of
     *  virtual dispatch). Returns the declaring class + method, or null when only a bridged/absent super has it.
     *  Memoized per (class, name+descriptor): the walk is deterministic and repeats on every call/recomposition.
     *  The VM is single-threaded (see the class note), so a plain map matches the existing class cache. */
    internal fun findInHierarchy(start: VmClass, name: String, descriptor: String): Pair<VmClass, org.objectweb.asm.tree.MethodNode>? {
        val perClass = resolveCache.getOrPut(start) { HashMap() }
        perClass[name + descriptor]?.let { return it.target }
        return findInHierarchyUncached(start, name, descriptor).also { perClass[name + descriptor] = Resolved(it) }
    }

    private fun findInHierarchyUncached(start: VmClass, name: String, descriptor: String): Pair<VmClass, org.objectweb.asm.tree.MethodNode>? {
        var c: VmClass? = start
        while (c != null) {
            val cur = c
            cur.declaredMethod(name, descriptor)?.let { return cur to it }
            c = cur.superName?.let { resolve(it) }
        }
        // interface default methods (interpreted interfaces only)
        return interfaceMethod(start, name, descriptor)
    }

    private fun interfaceMethod(cls: VmClass, name: String, descriptor: String): Pair<VmClass, org.objectweb.asm.tree.MethodNode>? {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        var c: VmClass? = cls
        while (c != null) { queue.addAll(c.interfaces); c = c.superName?.let { resolve(it) } }
        while (queue.isNotEmpty()) {
            val iface = queue.removeFirst()
            if (!seen.add(iface)) continue
            val ic = resolve(iface) ?: continue
            ic.declaredMethod(name, descriptor)?.takeIf { it.access and Opcodes.ACC_ABSTRACT == 0 }?.let { return ic to it }
            queue.addAll(ic.interfaces)
        }
        return null
    }

    /** Whether an interpreted value of runtime type [fromClass] is assignable to internal type [target]
     *  (for `checkcast`/`instanceof`/exception matching over interpreted types). */
    internal fun isSubtype(fromClass: VmClass, target: String): Boolean {
        if (target == "java/lang/Object") return true
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(fromClass.name)
        var c: VmClass? = fromClass
        // seed super chain + interfaces by name (names compared even for bridged supers)
        while (c != null) {
            if (c.name == target) return true
            queue.addAll(c.interfaces)
            val sn = c.superName
            if (sn != null && sn == target) return true
            c = sn?.let { resolve(it) }
        }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) continue
            if (n == target) return true
            resolve(n)?.let { queue.addAll(it.interfaces); it.superName?.let(queue::add) }
        }
        return false
    }
}
