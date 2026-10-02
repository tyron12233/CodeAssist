package dev.ide.vm

import kotlin.jvm.JvmField

/**
 * A closed-world JVM: it interprets EVERY class it runs, libraries and the Kotlin standard library
 * included, and the only code it does not read from [classPath] is the JDK, which [jdk] provides in Kotlin.
 *
 * That is the difference from `:jvm-interp`, which interprets the program and hands everything else to the
 * host JVM by reflection. A host with no JVM under it (iOS) has nothing to hand calls to and no reflection
 * to hand them with, so here the JDK is a fixed, finite floor written against the Kotlin standard library,
 * and everything above it, Compose included, is whatever version the project resolved.
 *
 * Values follow two lanes, primitive and reference (see [VmThread]): an `int` is a [Long] in the primitive
 * lane, a `String` is a Kotlin [String] in the reference lane, an `int[]` is an [IntArray], and an
 * instance of a class is a [VmObject]. Boxed primitives are Kotlin's own boxes (`Integer` is [Int]).
 *
 * Single-threaded for now: one [VmThread] runs everything, and `synchronized` only checks for null.
 */
class Vm(
    internal val classPath: ClassBytesSource,
    internal val jdk: JdkLibrary = JdkLibrary.standard(),
    /** Where `System.out`/`System.err` print. */
    internal val stdout: (String) -> Unit = { print(it) },
    /** Native method bodies and overrides for the libraries on the class path (see [HostBindings]). */
    private val bindings: HostBindings = HostBindings.NONE,
    /** What `System.getProperty` answers before the floor's defaults (`os.name`, for a library that asks). */
    internal val systemProperties: Map<String, String> = emptyMap(),
) {
    private val classes = HashMap<String, VmClass>()
    private val interned = HashMap<String, String>()
    internal val singletons = HashMap<String, VmObject>()
    internal var threadNumber = 0
    internal var timerGeneration = 0
    internal val proxyClasses = HashMap<String, VmClass>()
    private val queue = ArrayDeque<Task>()
    private val timers = ArrayList<Task>()
    private var taskSequence = 0L

    /** A unit of work on the VM's main queue: a `Runnable` to run, or a host action, due at [dueNanos]. */
    private class Task(val dueNanos: Long, val sequence: Long, val runnable: Any?, val action: (() -> Unit)?)
    internal val thread = VmThread(this)

    /** Interpreted instructions executed so far, across every call. */
    val steps: Long get() = thread.steps

    /** Classes linked so far. */
    val loadedClassCount: Int get() = classes.size

    /** The internal names of every class read from the class path so far (not the JDK floor's). */
    fun loadedClassNames(): List<String> = classes.values.filter { !it.isNative && !it.isArray && !it.isPrimitive }.map { it.name }

    internal val objectClass: VmClass by lazy { loadClass("java/lang/Object") }
    internal val classClass: VmClass by lazy { loadClass("java/lang/Class") }
    internal val stringClass: VmClass by lazy { loadClass("java/lang/String") }
    private val integerClass by lazy { loadClass("java/lang/Integer") }
    private val longClass by lazy { loadClass("java/lang/Long") }
    private val floatClass by lazy { loadClass("java/lang/Float") }
    private val doubleClass by lazy { loadClass("java/lang/Double") }
    private val booleanClass by lazy { loadClass("java/lang/Boolean") }
    private val charClass by lazy { loadClass("java/lang/Character") }
    private val byteClass by lazy { loadClass("java/lang/Byte") }
    private val shortClass by lazy { loadClass("java/lang/Short") }
    private val primitiveArrays = HashMap<Char, VmClass>()
    private val primitives = HashMap<Char, VmClass>()

    // ---- linking -------------------------------------------------------------------------------------

    /** The class named [name] (internal form, or an array descriptor), linked but not yet initialized. */
    fun loadClass(name: String): VmClass {
        classes[name]?.let { return it }
        return if (name[0] == '[') defineArray(name) else define(name)
    }

    /** The class a field or array-element descriptor names: `I` is `int`, `Ljava/lang/String;` is String. */
    internal fun classForDescriptor(descriptor: String): VmClass = when (descriptor[0]) {
        'L' -> loadClass(descriptor.substring(1, descriptor.length - 1))
        '[' -> loadClass(descriptor)
        else -> primitiveClass(descriptor[0])
    }

    internal fun primitiveClass(c: Char): VmClass = primitives.getOrPut(c) {
        val name = when (c) {
            'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"; 'Z' -> "boolean"
            'B' -> "byte"; 'C' -> "char"; 'S' -> "short"; 'V' -> "void"
            else -> error("primitive $c")
        }
        VmClass(this, name).also { it.primitive = c; it.access = ACC_PUBLIC or ACC_FINAL or ACC_ABSTRACT; it.initState = INIT_DONE }
    }

    private fun defineArray(name: String): VmClass {
        val cls = VmClass(this, name)
        cls.access = ACC_PUBLIC or ACC_FINAL or ACC_ABSTRACT
        cls.componentDescriptor = name.substring(1)
        classes[name] = cls
        cls.superClass = objectClass
        cls.interfaces = arrayOf(loadClass("java/lang/Cloneable"), loadClass("java/io/Serializable"))
        val component = name[1]
        if (component == 'L' || component == '[') cls.componentClass = classForDescriptor(cls.componentDescriptor!!)
        else { cls.componentClass = primitiveClass(component); primitiveArrays[component] = cls }
        cls.initState = INIT_DONE
        return cls
    }

    private fun define(name: String): VmClass {
        val spec = jdk.builder(name)
        if (spec != null) return defineNative(name, spec)
        if (isJdkName(name)) throw VmUnsupportedException("the JDK floor has no class $name")
        val bytes = classPath.read(name) ?: throwVm("java/lang/NoClassDefFoundError", name)
        return defineFromBytes(name, bytes)
    }

    /**
     * `ClassLoader.defineClass`: a class whose bytes the program made at run time (a generated peer, a
     * proxy). It is interpreted like any other, so defining one is safe on a host that forbids generating
     * code. The VM has one namespace; defining a name twice is a `LinkageError`.
     */
    internal fun defineClassBytes(bytes: ByteArray): VmClass {
        val parsed = ParsedClass.read(bytes)
        if (classes.containsKey(parsed.name)) throwVm("java/lang/LinkageError", "duplicate class definition: ${parsed.name}")
        return defineFromBytes(parsed.name, bytes)
    }

    private fun defineFromBytes(name: String, bytes: ByteArray): VmClass {
        val parsed = ParsedClass.read(bytes)
        if (parsed.name != name) throwVm("java/lang/NoClassDefFoundError", "$name (wrong name: ${parsed.name})")
        val cls = VmClass(this, name)
        cls.access = parsed.access
        cls.pool = parsed.pool
        cls.parsed = parsed
        cls.sourceFile = parsed.sourceFile
        cls.resolved = arrayOfNulls(parsed.pool.size)
        classes[name] = cls
        try {
            cls.superClass = parsed.superName?.let { loadClass(it) }
            cls.interfaces = if (parsed.interfaces.isEmpty()) VmClass.NO_CLASSES
            else Array(parsed.interfaces.size) { loadClass(parsed.interfaces[it]) }
        } catch (e: Throwable) {
            classes.remove(name)
            throw e
        }
        layoutFields(cls, parsed.fields.map { Triple(it.name, it.descriptor, it.access) })
        for (f in parsed.fields) if (f.signature != null) cls.fields["${f.name}:${f.descriptor}"]?.signature = f.signature
        for (f in parsed.fields) {
            if (f.access and ACC_STATIC == 0 || f.constantValue == 0) continue
            val field = cls.fields["${f.name}:${f.descriptor}"]!!
            val pool = parsed.pool
            when (pool.tags[f.constantValue].toInt()) {
                ConstantPool.INTEGER, ConstantPool.FLOAT -> cls.staticPrims[field.slot] = pool.a[f.constantValue].toLong()
                ConstantPool.LONG, ConstantPool.DOUBLE -> cls.staticPrims[field.slot] = pool.wide[f.constantValue]
                ConstantPool.STRING -> cls.staticRefs[field.slot] = intern(pool.string(f.constantValue))
            }
        }
        for (pm in parsed.methods) {
            val m = VmMethod(cls, pm.name, pm.descriptor, pm.access)
            m.code = pm.code
            m.maxStack = pm.maxStack
            m.maxLocals = pm.maxLocals
            m.handlers = pm.handlers
            m.lines = pm.lines
            m.signature = pm.signature
            m.native = Threading.override(this, name, pm.name, pm.descriptor) ?: bindings.override(this, name, pm.name, pm.descriptor)
            cls.methods[m.key] = m
        }
        return cls
    }

    private fun defineNative(name: String, spec: NativeClassBuilder): VmClass {
        val cls = VmClass(this, name)
        cls.isNative = true
        cls.access = spec.access
        cls.valueBacked = spec.valueBacked
        classes[name] = cls
        try {
            cls.superClass = spec.superName?.let { loadClass(it) }
            cls.interfaces = Array(spec.interfaces.size) { loadClass(spec.interfaces[it]) }
        } catch (e: Throwable) {
            classes.remove(name)
            throw e
        }
        layoutFields(cls, spec.fields)
        for (ms in spec.methods) {
            val m = VmMethod(cls, ms.name, ms.descriptor, ms.access)
            m.native = ms.impl
            cls.methods[m.key] = m
        }
        cls.nativeStaticInit = spec.staticInit
        return cls
    }

    private fun layoutFields(cls: VmClass, declared: List<Triple<String, String, Int>>) {
        var prims = cls.superClass?.primSlots ?: 0
        var refs = cls.superClass?.refSlots ?: 0
        var staticPrims = 0
        var staticRefs = 0
        for ((name, desc, access) in declared) {
            val isRef = desc[0] == 'L' || desc[0] == '['
            val slot = if (access and ACC_STATIC != 0) {
                if (isRef) staticRefs++ else staticPrims++
            } else {
                if (isRef) refs++ else prims++
            }
            cls.fields["$name:$desc"] = VmField(cls, name, desc, access, slot)
        }
        cls.primSlots = prims
        cls.refSlots = refs
        if (staticPrims > 0) cls.staticPrims = LongArray(staticPrims)
        if (staticRefs > 0) cls.staticRefs = arrayOfNulls(staticRefs)
    }

    private fun isJdkName(name: String): Boolean =
        name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/")

    internal fun intern(s: String): String = interned.getOrPut(s) { s }

    // ---- initialization ------------------------------------------------------------------------------

    internal fun ensureInitialized(cls: VmClass) {
        if (cls.initState == INIT_DONE || cls.initState == INIT_RUNNING) return
        if (cls.initState == INIT_FAILED) throwVm("java/lang/NoClassDefFoundError", "Could not initialize class ${cls.javaName}")
        cls.initState = INIT_RUNNING
        try {
            val sup = cls.superClass
            if (sup != null && !cls.isInterface) ensureInitialized(sup)
            cls.nativeStaticInit?.let { init -> withCall { it.init(cls) } }
            cls.methods["<clinit>()V"]?.let { callMethod(it) }
            cls.initState = INIT_DONE
        } catch (e: VmThrow) {
            cls.initState = INIT_FAILED
            (e.obj.native as? ThrowableState)?.let { if (it.trace.isEmpty()) it.trace = e.trace.toList() + "<clinit> of ${cls.javaName}" }
            if (isInstance(e.obj, loadClass("java/lang/Error"))) throw e
            val wrapped = newThrowable("java/lang/ExceptionInInitializerError", null)
            (wrapped.native as ThrowableState).apply { cause = e.obj; causeSet = true }
            throw VmThrow(wrapped).also { it.trace.addAll(e.trace) }
        } catch (e: Throwable) {
            cls.initState = INIT_NONE
            throw e
        }
    }

    // ---- resolution ----------------------------------------------------------------------------------

    /** The method [key] as resolution sees it from [cls]: the class chain first, then superinterfaces. */
    internal fun findMethod(cls: VmClass, key: String): VmMethod? {
        var c: VmClass? = cls
        while (c != null) {
            c.methods[key]?.let { return it }
            c = c.superClass
        }
        return findInInterfaces(cls, key, HashSet())
    }

    private fun findInInterfaces(cls: VmClass, key: String, seen: HashSet<VmClass>): VmMethod? {
        var c: VmClass? = cls
        var abstractHit: VmMethod? = null
        while (c != null) {
            for (i in c.interfaces) {
                if (!seen.add(i)) continue
                val m = i.methods[key]
                if (m != null && !m.isAbstract && !m.isStatic) return m
                if (m != null && abstractHit == null && !m.isStatic) abstractHit = m
                val inner = findInInterfaces(i, key, seen)
                if (inner != null && !inner.isAbstract) return inner
                if (inner != null && abstractHit == null) abstractHit = inner
            }
            c = c.superClass
        }
        return abstractHit
    }

    /** The method an `invokevirtual`/`invokeinterface` of [key] runs on an instance of [cls]. */
    internal fun selectVirtual(cls: VmClass, key: String): VmMethod? {
        val cached = cls.virtuals[key]
        if (cached != null || cls.virtuals.containsKey(key)) return cached
        var selected: VmMethod? = null
        var c: VmClass? = cls
        while (c != null) {
            val m = c.methods[key]
            if (m != null && !m.isStatic && m.name != "<init>") { selected = m; break }
            c = c.superClass
        }
        if (selected == null || selected.isAbstract) {
            val fromInterface = findInInterfaces(cls, key, HashSet())
            if (fromInterface != null && !fromInterface.isAbstract) selected = fromInterface
            else if (selected == null) selected = fromInterface
        }
        cls.virtuals[key] = selected
        return selected
    }

    internal fun findField(cls: VmClass, name: String, descriptor: String): VmField? {
        val key = "$name:$descriptor"
        var c: VmClass? = cls
        while (c != null) {
            c.fields[key]?.let { return it }
            for (i in c.interfaces) findField(i, name, descriptor)?.let { return it }
            c = c.superClass
        }
        return null
    }

    // ---- types ---------------------------------------------------------------------------------------

    /** The VM class of a value in the reference lane. */
    fun classOf(value: Any): VmClass = when (value) {
        is VmObject -> value.cls
        is String -> stringClass
        is VmRefArray -> value.cls
        is HostObject -> value.vmClass
        is Int -> integerClass
        is Long -> longClass
        is Boolean -> booleanClass
        is Char -> charClass
        is Float -> floatClass
        is Double -> doubleClass
        is Byte -> byteClass
        is Short -> shortClass
        is IntArray -> primitiveArray('I')
        is LongArray -> primitiveArray('J')
        is ByteArray -> primitiveArray('B')
        is CharArray -> primitiveArray('C')
        is FloatArray -> primitiveArray('F')
        is DoubleArray -> primitiveArray('D')
        is BooleanArray -> primitiveArray('Z')
        is ShortArray -> primitiveArray('S')
        is Uninit -> value.cls
        else -> throw VmUnsupportedException("no VM class for host value ${value::class}")
    }

    private fun primitiveArray(c: Char): VmClass = primitiveArrays[c] ?: loadClass("[$c")

    internal fun isInstance(value: Any, target: VmClass): Boolean {
        if (value is VmObject && value.cls === target) return true
        return isAssignable(classOf(value), target)
    }

    internal fun isAssignable(from: VmClass, to: VmClass): Boolean {
        if (from === to) return true
        if (from.isPrimitive || to.isPrimitive) return false
        if (to.name == "java/lang/Object") return true
        if (from.isArray) {
            if (to.isArray) {
                val fc = from.componentClass!!
                val tc = to.componentClass!!
                if (fc.isPrimitive || tc.isPrimitive) return fc === tc
                return isAssignable(fc, tc)
            }
            return to.name == "java/lang/Cloneable" || to.name == "java/io/Serializable"
        }
        return supertypes(from).contains(to)
    }

    private fun supertypes(cls: VmClass): HashSet<VmClass> {
        cls.supertypeSet?.let { return it }
        val set = HashSet<VmClass>()
        fun add(c: VmClass) {
            if (!set.add(c)) return
            c.superClass?.let { add(it) }
            for (i in c.interfaces) add(i)
        }
        add(cls)
        cls.supertypeSet = set
        return set
    }

    /** The `java.lang.Class` object for [cls]. */
    internal fun mirror(cls: VmClass): VmObject {
        cls.mirror?.let { return it }
        val m = VmObject(classClass)
        m.native = cls
        cls.mirror = m
        return m
    }

    // ---- allocation ----------------------------------------------------------------------------------

    internal fun newObject(cls: VmClass): VmObject = VmObject(cls)

    /** A new array whose elements are [componentDescriptor]: a Kotlin primitive array, or a [VmRefArray]. */
    internal fun newArray(componentDescriptor: String, length: Int): Any {
        if (length < 0) throwVm("java/lang/NegativeArraySizeException", length.toString())
        return when (componentDescriptor[0]) {
            'I' -> IntArray(length)
            'J' -> LongArray(length)
            'F' -> FloatArray(length)
            'D' -> DoubleArray(length)
            'B' -> ByteArray(length)
            'C' -> CharArray(length)
            'S' -> ShortArray(length)
            'Z' -> BooleanArray(length)
            else -> VmRefArray(loadClass("[$componentDescriptor"), arrayOfNulls(length))
        }
    }

    fun newRefArray(componentInternalName: String, data: Array<Any?>): VmRefArray =
        VmRefArray(loadClass(if (componentInternalName[0] == '[') "[$componentInternalName" else "[L$componentInternalName;"), data)

    // ---- throwables ----------------------------------------------------------------------------------

    internal fun newThrowable(className: String, message: String?): VmObject {
        val cls = loadClass(className)
        ensureInitialized(cls)
        val obj = VmObject(cls)
        obj.native = ThrowableState(message)
        return obj
    }

    internal fun throwVm(className: String, message: String?): Nothing = throw VmThrow(newThrowable(className, message))

    internal fun throwNpe(what: String? = null): Nothing = throwVm("java/lang/NullPointerException", what)

    // ---- calls ---------------------------------------------------------------------------------------

    /** Runs [m] with its arguments already in place at [base]; the result, if any, is left at [base]. */
    internal fun invoke(m: VmMethod, base: Int) {
        val n = m.native
        if (n != null) {
            val t = thread
            val call = t.call
            val savedBp = call.bp
            val savedTop = t.top
            call.bp = base
            t.top = base + m.argWords + 2
            // Restored even when the native throws: an interpreted handler may catch it and carry on, and a
            // caller further out must not see this call's frame base as its own.
            try {
                n.invoke(call)
            } finally {
                call.bp = savedBp
                t.top = savedTop
            }
            return
        }
        if (m.code == null) {
            if (m.access and ACC_NATIVE != 0 && !m.owner.isNative) {
                val bound = bindings.native(this, m.owner.name, m.name, m.descriptor)
                    ?: throw VmUnsupportedException("no host binding for native method ${m.owner.name}.${m.key}")
                m.native = bound
                invoke(m, base)
                return
            }
            if (m.owner.isNative) throw VmUnsupportedException("the JDK floor does not implement ${m.owner.name}.${m.key}")
            throwVm("java/lang/AbstractMethodError", "${m.owner.javaName}.${m.key}")
        }
        execute(m, thread, base)
    }

    private inline fun withCall(block: (Call) -> Unit) {
        val t = thread
        val call = t.call
        val savedBp = call.bp
        call.bp = t.top
        block(call)
        call.bp = savedBp
    }

    /**
     * Calls [m] from Kotlin (a native, the host API, a class initializer) with [args] boxed: an `int`
     * parameter takes an [Int] (or a [Boolean]/[Char]), a reference parameter the value itself. Returns the
     * result boxed the same way (a `boolean` comes back as [Boolean], a `char` as [Char]).
     */
    internal fun callMethod(m: VmMethod, vararg args: Any?): Any? = callMethodImpl(m, args, receiverResult = false)

    /** [callMethod] for a constructor of a value-backed class: the result is what the constructor produced. */
    internal fun callConstructorForValue(m: VmMethod, vararg args: Any?): Any? = callMethodImpl(m, args, receiverResult = true)

    private fun callMethodImpl(m: VmMethod, args: Array<out Any?>, receiverResult: Boolean): Any? {
        val t = thread
        val base = t.top
        var w = base
        var argIndex = 0
        if (!m.isStatic) { t.r[w++] = args[0]; argIndex = 1 }
        for (k in m.paramKinds) {
            val a = args[argIndex++]
            when (k) {
                K_INT -> t.p[w++] = when (a) {
                    is Int -> a.toLong(); is Boolean -> if (a) 1L else 0L; is Char -> a.code.toLong()
                    is Byte -> a.toLong(); is Short -> a.toLong()
                    else -> error("int argument $a")
                }
                K_LONG -> { t.p[w] = (a as Long); w += 2 }
                K_FLOAT -> t.p[w++] = (a as Float).toRawBits().toLong()
                K_DOUBLE -> { t.p[w] = (a as Double).toRawBits(); w += 2 }
                else -> t.r[w++] = a
            }
        }
        val savedTop = t.top
        t.top = w + 2
        try {
            invoke(m, base)
        } finally {
            t.top = savedTop
        }
        if (receiverResult) return t.r[base].also { t.r[base] = null }
        return result(m, base)
    }

    private fun result(m: VmMethod, base: Int): Any? {
        val t = thread
        return when (m.descriptor[m.descriptor.indexOf(')') + 1]) {
            'V' -> Unit
            'Z' -> t.p[base] != 0L
            'C' -> t.p[base].toInt().toChar()
            'B' -> t.p[base].toInt().toByte()
            'S' -> t.p[base].toInt().toShort()
            'I' -> t.p[base].toInt()
            'J' -> t.p[base]
            'F' -> Float.fromBits(t.p[base].toInt())
            'D' -> Double.fromBits(t.p[base])
            else -> t.r[base].also { t.r[base] = null }
        }
    }

    /** Invokes [name]/[descriptor] virtually on [receiver]. */
    internal fun callVirtual(receiver: Any?, name: String, descriptor: String, vararg args: Any?): Any? {
        if (receiver == null) throwNpe("calling $name on null")
        val m = selectVirtual(classOf(receiver), name + descriptor)
            ?: throwVm("java/lang/AbstractMethodError", "${classOf(receiver).javaName}.$name$descriptor")
        return when (args.size) {
            0 -> callMethod(m, receiver)
            1 -> callMethod(m, receiver, args[0])
            2 -> callMethod(m, receiver, args[0], args[1])
            else -> {
                val all = arrayOfNulls<Any?>(args.size + 1)
                all[0] = receiver
                args.copyInto(all, 1)
                callMethod(m, *all)
            }
        }
    }

    // ---- Object protocol, with fast paths for the values the floor represents directly -----------------

    internal fun vmEquals(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return when (a) {
            is String, is Int, is Long, is Boolean, is Char, is Byte, is Short -> a == b
            is Float -> b is Float && a.toBits() == b.toBits()
            is Double -> b is Double && a.toBits() == b.toBits()
            else -> {
                val m = selectVirtual(classOf(a), "equals(Ljava/lang/Object;)Z")!!
                if (m.owner === objectClass) false else callMethod(m, a, b) as Boolean
            }
        }
    }

    internal fun vmHashCode(a: Any?): Int = when (a) {
        null -> 0
        is String -> javaStringHash(a)
        is Int -> a
        is Long -> (a xor (a ushr 32)).toInt()
        is Boolean -> if (a) 1231 else 1237
        is Char -> a.code
        is Byte -> a.toInt()
        is Short -> a.toInt()
        is Float -> a.toBits()
        is Double -> a.toBits().let { (it xor (it ushr 32)).toInt() }
        else -> {
            val m = selectVirtual(classOf(a), "hashCode()I")!!
            if (m.owner === objectClass) identityHash(a) else callMethod(m, a) as Int
        }
    }

    internal fun vmToString(a: Any?): String = when (a) {
        null -> "null"
        is String -> a
        is Int, is Long, is Boolean, is Byte, is Short -> a.toString()
        is Char -> a.toString()
        is Float -> javaFloatToString(a)
        is Double -> javaDoubleToString(a)
        else -> (callVirtual(a, "toString", "()Ljava/lang/String;") as String?) ?: "null"
    }

    // ---- the main queue ------------------------------------------------------------------------------

    /** Queues an interpreted `Runnable` to run on the VM's main queue after [delayMillis]. */
    internal fun post(runnable: Any, delayMillis: Long) {
        val task = Task(nanoTime() + delayMillis * 1_000_000, taskSequence++, runnable, null)
        if (delayMillis <= 0) queue.addLast(task) else timers.add(task)
    }

    internal fun schedule(delayMillis: Long, action: () -> Unit) {
        val task = Task(nanoTime() + delayMillis * 1_000_000, taskSequence++, null, action)
        if (delayMillis <= 0) queue.addLast(task) else timers.add(task)
    }

    /**
     * Runs what is queued on the VM's main thread, including what those tasks queue and timers that come due
     * meanwhile, until nothing is ready. Returns how many ran. The host calls this where a desktop app's
     * event thread would run: after a call into the VM, between frames.
     */
    fun runPendingTasks(): Int {
        var ran = 0
        while (true) {
            val now = nanoTime()
            val due = timers.filter { it.dueNanos <= now }.sortedWith(compareBy({ it.dueNanos }, { it.sequence }))
            if (due.isNotEmpty()) { timers.removeAll(due.toSet()); for (t in due) queue.addLast(t) }
            val task = queue.removeFirstOrNull() ?: return ran
            ran++
            try {
                if (task.action != null) task.action.invoke()
                else callVirtual(task.runnable, "run", "()V")
            } catch (e: VmThrow) {
                throw hostException(e.obj, e.trace)
            }
        }
    }

    /** Milliseconds until the next timer is due, or null when none is pending. */
    val nextTimerDelayMillis: Long?
        get() = timers.minOfOrNull { it.dueNanos }?.let { ((it - nanoTime()) / 1_000_000).coerceAtLeast(0) }

    // ---- host API ------------------------------------------------------------------------------------

    /** Whether [value] is a Kotlin `() -> R`: what a library hands a native as a callback. */
    fun isFunction0(value: Any): Boolean = isInstance(value, loadClass("kotlin/jvm/functions/Function0"))

    /** Calls a Kotlin `() -> R` from host code; an interpreted exception comes back as a [VmException]. */
    fun invokeFunction0(function: Any): Any? = invokeVirtualPublic(function, "invoke", "()Ljava/lang/Object;")

    /** Calls [name]/[descriptor] on [receiver] from host code, boxing as [invokeStatic] does. */
    fun invokeVirtualPublic(receiver: Any, name: String, descriptor: String, vararg args: Any?): Any? = try {
        callVirtual(receiver, name, descriptor, *args)
    } catch (e: VmThrow) {
        throw hostException(e.obj, e.trace)
    }

    /** The `Companion` object of [className] (an interpreted Kotlin class), initialized. */
    fun companionOf(className: String): Any {
        val cls = loadClass(className)
        ensureInitialized(cls)
        val f = findField(cls, "Companion", "L$className\$Companion;") ?: throw VmUnsupportedException("$className has no Companion")
        return cls.staticRefs[f.slot] ?: throw VmUnsupportedException("$className.Companion is null")
    }

    /**
     * Calls the static method [owner].[name][descriptor] with boxed [args] (see [callMethod]) and returns
     * its boxed result. An interpreted exception that escapes comes back as a [VmException].
     */
    fun invokeStatic(owner: String, name: String, descriptor: String, vararg args: Any?): Any? {
        val cls = loadClass(owner)
        try {
            ensureInitialized(cls)
            val m = findMethod(cls, name + descriptor)
                ?: throw VmUnsupportedException("no method $owner.$name$descriptor")
            return callMethod(m, *args)
        } catch (e: VmThrow) {
            throw hostException(e.obj, e.trace)
        }
    }

    internal fun hostException(obj: VmObject, trace: List<String>): VmException {
        val state = obj.native as? ThrowableState
        val cause = state?.cause as? VmObject
        return VmException(
            obj.cls.name,
            state?.message,
            trace,
            if (cause != null && cause !== obj) hostException(cause, (cause.native as? ThrowableState)?.trace ?: emptyList()) else null,
        )
    }
}

/**
 * A thread's stack: one primitive lane and one reference lane, shared by every frame.
 *
 * A frame is a window `[bp, bp + maxLocals + maxStack)`. A call does not copy its arguments: the caller
 * pushes them on its operand stack and the callee's locals START there, so the callee's `bp` is the
 * caller's stack pointer minus the arguments. The result is written back at that same `bp`. A `long` or
 * `double` takes two words, as in the class file, with its value in the lower one.
 */
class VmThread internal constructor(val vm: Vm, size: Int = 1 shl 18) {
    @JvmField internal val p = LongArray(size)
    @JvmField internal val r = arrayOfNulls<Any?>(size)

    /** First free word for a call made from Kotlin (a native calling back, a class initializer). */
    @JvmField internal var top = 0
    @JvmField internal var depth = 0
    @JvmField internal var steps = 0L
    internal val call = Call(vm, this)
}

/** The view a [Native] gets of its arguments, by word index (the receiver is word 0), and of its result. */
class Call internal constructor(val vm: Vm, private val t: VmThread) {
    internal var bp = 0

    fun i(n: Int): Int = t.p[bp + n].toInt()
    fun z(n: Int): Boolean = t.p[bp + n] != 0L
    fun c(n: Int): Char = t.p[bp + n].toInt().toChar()
    fun l(n: Int): Long = t.p[bp + n]
    fun f(n: Int): Float = Float.fromBits(t.p[bp + n].toInt())
    fun d(n: Int): Double = Double.fromBits(t.p[bp + n])
    fun r(n: Int): Any? = t.r[bp + n]

    /** The receiver, as the [VmObject] a subclassable floor class is. */
    fun self(): VmObject = t.r[bp] as VmObject

    fun ret(v: Int) { t.p[bp] = v.toLong() }
    fun ret(v: Boolean) { t.p[bp] = if (v) 1L else 0L }
    fun ret(v: Char) { t.p[bp] = v.code.toLong() }
    fun ret(v: Long) { t.p[bp] = v }
    fun ret(v: Float) { t.p[bp] = v.toRawBits().toLong() }
    fun ret(v: Double) { t.p[bp] = v.toRawBits() }
    fun retRef(v: Any?) { t.r[bp] = v }
}

/** Java's `String.hashCode`, which differs from nothing in practice but is specified, so it is computed. */
internal fun javaStringHash(s: String): Int {
    var h = 0
    for (ch in s) h = 31 * h + ch.code
    return h
}

internal expect fun identityHash(o: Any): Int
internal expect fun javaFloatToString(f: Float): String
internal expect fun javaDoubleToString(d: Double): String
internal expect fun nanoTime(): Long
