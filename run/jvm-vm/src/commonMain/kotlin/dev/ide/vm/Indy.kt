package dev.ide.vm

/** A linked `invokedynamic` call site. [call] consumes its arguments below [sp] and returns the new stack pointer. */
internal abstract class IndySite {
    abstract fun call(vm: Vm, t: VmThread, sp: Int): Int
}

private const val REF_INVOKE_VIRTUAL = 5
private const val REF_INVOKE_STATIC = 6
private const val REF_INVOKE_SPECIAL = 7
private const val REF_NEW_INVOKE_SPECIAL = 8
private const val REF_INVOKE_INTERFACE = 9

private const val FLAG_MARKERS = 2
private const val FLAG_BRIDGES = 4

internal fun Vm.linkIndy(cls: VmClass, index: Int): IndySite {
    val pool = cls.pool!!
    val bootstrap = cls.parsed!!.bootstrapMethods[pool.a[index]]
    val name = pool.nameAndTypeName(pool.b[index])
    val descriptor = pool.nameAndTypeDescriptor(pool.b[index])
    val handleRef = pool.b[bootstrap.handle]
    val owner = pool.memberOwner(handleRef)
    val method = pool.memberName(handleRef)
    val site = when {
        owner == "java/lang/invoke/LambdaMetafactory" && (method == "metafactory" || method == "altMetafactory") ->
            linkLambda(cls, index, name, descriptor, bootstrap, alt = method == "altMetafactory")
        owner == "java/lang/invoke/StringConcatFactory" && method == "makeConcatWithConstants" -> {
            val recipe = pool.string(bootstrap.arguments[0])
            val constants = bootstrap.arguments.drop(1).map { constant(cls, it) }
            ConcatSite(recipe, constants, parameterDescriptors(descriptor))
        }
        owner == "java/lang/invoke/StringConcatFactory" && method == "makeConcat" ->
            ConcatSite(null, emptyList(), parameterDescriptors(descriptor))
        else -> throw VmUnsupportedException("invokedynamic bootstrap $owner.$method in ${cls.name}")
    }
    cls.resolved[index] = site
    return site
}

private fun Vm.constant(cls: VmClass, index: Int): Any? {
    val pool = cls.pool!!
    return when (pool.tags[index].toInt()) {
        ConstantPool.STRING -> pool.string(index)
        ConstantPool.INTEGER -> pool.a[index]
        ConstantPool.FLOAT -> Float.fromBits(pool.a[index])
        ConstantPool.LONG -> pool.wide[index]
        ConstantPool.DOUBLE -> Double.fromBits(pool.wide[index])
        ConstantPool.CLASS -> mirror(classRef(cls, index))
        else -> throw VmUnsupportedException("bootstrap constant tag ${pool.tags[index]}")
    }
}

private fun methodTypeOf(pool: ConstantPool, index: Int): String = pool.utf8(pool.a[index])

/**
 * Links a `LambdaMetafactory` site by defining a class on the spot: it implements the functional interface
 * (and any marker interfaces), keeps the captured values in its fields, and answers the interface method
 * (and its bridges) with a [Native] that forwards to the implementation method. No class is generated as
 * bytecode; the class exists only in the VM.
 */
private fun Vm.linkLambda(
    cls: VmClass,
    index: Int,
    samName: String,
    indyDescriptor: String,
    bootstrap: BootstrapMethod,
    alt: Boolean,
): IndySite {
    val pool = cls.pool!!
    val args = bootstrap.arguments
    val samDescriptors = ArrayList<String>()
    samDescriptors.add(methodTypeOf(pool, args[0]))
    val implHandle = args[1]
    val instantiated = methodTypeOf(pool, args[2])
    val interfaces = ArrayList<VmClass>()
    val interfaceName = returnDescriptor(indyDescriptor).let { it.substring(1, it.length - 1) }
    interfaces.add(loadClass(interfaceName))
    if (alt) {
        val flags = pool.a[args[3]]
        var at = 4
        if (flags and FLAG_MARKERS != 0) {
            val count = pool.a[args[at++]]
            repeat(count) { interfaces.add(classRef(cls, args[at++])) }
        }
        if (flags and FLAG_BRIDGES != 0) {
            val count = pool.a[args[at++]]
            repeat(count) { samDescriptors.add(methodTypeOf(pool, args[at++])) }
        }
    }
    if (instantiated !in samDescriptors) samDescriptors.add(instantiated)

    val implKind = pool.a[implHandle]
    val implRef = pool.b[implHandle]
    val impl = ImplRef(
        kind = implKind,
        owner = pool.memberOwner(implRef),
        key = pool.memberName(implRef) + pool.memberDescriptor(implRef),
        descriptor = pool.memberDescriptor(implRef),
    )

    val captured = parameterDescriptors(indyDescriptor)
    val lambda = VmClass(this, "${cls.name}\$\$Lambda\$$index")
    lambda.isNative = true
    lambda.access = ACC_PUBLIC or ACC_FINAL
    lambda.superClass = objectClass
    lambda.interfaces = interfaces.toTypedArray()
    lambda.initState = INIT_DONE
    var prims = 0
    var refs = 0
    val captureSlots = IntArray(captured.size) { k ->
        val d = captured[k]
        if (d[0] == 'L' || d[0] == '[') refs++ else prims++
    }
    lambda.primSlots = prims
    lambda.refSlots = refs

    val implParams = parameterDescriptors(impl.descriptor)
    val targets: List<String> = when (implKind) {
        REF_INVOKE_STATIC, REF_NEW_INVOKE_SPECIAL -> implParams
        else -> listOf("L${impl.owner};") + implParams
    }
    for (samDesc in samDescriptors) {
        val m = VmMethod(lambda, samName, samDesc, ACC_PUBLIC)
        m.native = LambdaBody(this, impl, captured, captureSlots, parameterDescriptors(samDesc), returnDescriptor(samDesc), targets)
        lambda.methods[m.key] = m
    }
    return LambdaSite(lambda, captured, captureSlots)
}

private class ImplRef(val kind: Int, val owner: String, val key: String, val descriptor: String) {
    var method: VmMethod? = null
    var ownerClass: VmClass? = null
}

private class LambdaSite(val lambda: VmClass, val captured: List<String>, val slots: IntArray) : IndySite() {
    private val words = captured.sumOf { wordsOf(kindOf(it[0])) }
    override fun call(vm: Vm, t: VmThread, sp: Int): Int {
        val base = sp - words
        val o = VmObject(lambda)
        var w = base
        for (k in captured.indices) {
            val d = captured[k]
            if (d[0] == 'L' || d[0] == '[') { o.refs[slots[k]] = t.r[w]; w++ }
            else { o.prims[slots[k]] = t.p[w]; w += if (d == "J" || d == "D") 2 else 1 }
        }
        t.r[base] = o
        return base + 1
    }
}

private class LambdaBody(
    private val vm: Vm,
    private val impl: ImplRef,
    private val captured: List<String>,
    private val captureSlots: IntArray,
    private val samParams: List<String>,
    private val samReturn: String,
    private val targets: List<String>,
) : Native {
    override fun invoke(call: Call) {
        val t = vm.thread
        val self = call.self()
        val result = call.bp
        val base = t.top
        var w = base
        var ti = 0
        if (impl.kind == REF_NEW_INVOKE_SPECIAL) {
            val owner = impl.ownerClass ?: vm.loadClass(impl.owner).also { impl.ownerClass = it }
            vm.ensureInitialized(owner)
            t.r[w++] = if (owner.valueBacked) Uninit(owner) else VmObject(owner)
        }
        for (k in captured.indices) {
            val d = captured[k]
            val isRef = d[0] == 'L' || d[0] == '['
            w = adapt(t, w, d, targets[ti++], if (isRef) 0L else self.prims[captureSlots[k]], if (isRef) self.refs[captureSlots[k]] else null)
        }
        var aw = call.bp + 1
        for (d in samParams) {
            val isRef = d[0] == 'L' || d[0] == '['
            w = adapt(t, w, d, targets[ti++], t.p[aw], t.r[aw])
            aw += if (!isRef && (d == "J" || d == "D")) 2 else 1
        }
        val m = target(t, base)
        val savedTop = t.top
        t.top = w + 2
        vm.invoke(m, base)
        t.top = savedTop
        val implReturn = if (impl.kind == REF_NEW_INVOKE_SPECIAL) "L${impl.owner};" else returnDescriptor(impl.descriptor)
        if (impl.kind == REF_NEW_INVOKE_SPECIAL) {
            // The constructor returns void; the result is the object it initialized (or the value it produced).
            t.r[result] = t.r[base]
            return
        }
        when {
            samReturn == "V" -> {}
            implReturn == "V" -> t.r[result] = null
            else -> adapt(t, result, implReturn, samReturn, t.p[base], t.r[base])
        }
    }

    private fun target(t: VmThread, base: Int): VmMethod {
        impl.method?.let { if (impl.kind != REF_INVOKE_VIRTUAL && impl.kind != REF_INVOKE_INTERFACE) return it }
        val owner = impl.ownerClass ?: vm.loadClass(impl.owner).also { impl.ownerClass = it }
        return when (impl.kind) {
            REF_INVOKE_VIRTUAL, REF_INVOKE_INTERFACE -> {
                val recv = t.r[base] ?: vm.throwNpe("lambda receiver")
                vm.selectVirtual(vm.classOf(recv), impl.key)
                    ?: vm.throwVm("java/lang/AbstractMethodError", impl.key)
            }
            else -> {
                if (impl.kind == REF_INVOKE_STATIC) vm.ensureInitialized(owner)
                val m = vm.findMethod(owner, impl.key)
                    ?: throw VmUnsupportedException("lambda implementation ${impl.owner}.${impl.key} not found")
                impl.method = m
                m
            }
        }
    }

    /** Writes a value of type [from] (held as [prim]/[ref]) at [at] as type [to]; returns the next word. */
    private fun adapt(t: VmThread, at: Int, from: String, to: String, prim: Long, ref: Any?): Int {
        val fromRef = from[0] == 'L' || from[0] == '['
        val toRef = to[0] == 'L' || to[0] == '['
        when {
            fromRef && toRef -> t.r[at] = ref
            !fromRef && toRef -> t.r[at] = box(from[0], prim)
            fromRef && !toRef -> t.p[at] = unbox(ref, to[0])
            else -> t.p[at] = widen(prim, from[0], to[0])
        }
        return at + if (!toRef && (to == "J" || to == "D")) 2 else 1
    }

    private fun box(kind: Char, v: Long): Any = when (kind) {
        'Z' -> v != 0L
        'C' -> v.toInt().toChar()
        'B' -> v.toInt().toByte()
        'S' -> v.toInt().toShort()
        'I' -> v.toInt()
        'J' -> v
        'F' -> Float.fromBits(v.toInt())
        'D' -> Double.fromBits(v)
        else -> error("box $kind")
    }

    private fun unbox(v: Any?, to: Char): Long {
        if (v == null) vm.throwNpe("unboxing null")
        val asLong: Long
        val asDouble: Double
        when (v) {
            is Boolean -> return if (v) 1L else 0L
            is Char -> { asLong = v.code.toLong(); asDouble = asLong.toDouble() }
            is Int -> { asLong = v.toLong(); asDouble = v.toDouble() }
            is Byte -> { asLong = v.toLong(); asDouble = v.toDouble() }
            is Short -> { asLong = v.toLong(); asDouble = v.toDouble() }
            is Long -> { asLong = v; asDouble = v.toDouble() }
            is Float -> return when (to) { 'F' -> v.toRawBits().toLong(); 'D' -> v.toDouble().toRawBits(); else -> v.toLong() }
            is Double -> return when (to) { 'D' -> v.toRawBits(); 'F' -> v.toFloat().toRawBits().toLong(); else -> v.toLong() }
            else -> vm.throwVm("java/lang/ClassCastException", "${vm.classOf(v).javaName} cannot be unboxed")
        }
        return when (to) {
            'F' -> asDouble.toFloat().toRawBits().toLong()
            'D' -> asDouble.toRawBits()
            else -> asLong
        }
    }

    private fun widen(v: Long, from: Char, to: Char): Long {
        val fromInt = from != 'J' && from != 'F' && from != 'D'
        val toInt = to != 'J' && to != 'F' && to != 'D'
        if (from == to || (fromInt && toInt)) return v
        return when (to) {
            'J' -> when (from) { 'F' -> Float.fromBits(v.toInt()).toLong(); 'D' -> Double.fromBits(v).toLong(); else -> v }
            'F' -> when (from) { 'D' -> Double.fromBits(v).toFloat(); 'J' -> v.toFloat(); else -> v.toInt().toFloat() }.toRawBits().toLong()
            'D' -> when (from) { 'F' -> Float.fromBits(v.toInt()).toDouble(); 'J' -> v.toDouble(); else -> v.toInt().toDouble() }.toRawBits()
            else -> v
        }
    }
}

/** `StringConcatFactory`: `"a" + x + "b"` compiled to a recipe where `\u0001` is an argument and `\u0002` a constant. */
private class ConcatSite(val recipe: String?, val constants: List<Any?>, val params: List<String>) : IndySite() {
    private val words = params.sumOf { wordsOf(kindOf(it[0])) }

    override fun call(vm: Vm, t: VmThread, sp: Int): Int {
        val base = sp - words
        val values = arrayOfNulls<String>(params.size)
        var w = base
        for (k in params.indices) {
            val d = params[k]
            values[k] = when (d[0]) {
                'Z' -> (t.p[w] != 0L).toString()
                'C' -> t.p[w].toInt().toChar().toString()
                'B', 'S', 'I' -> t.p[w].toInt().toString()
                'J' -> t.p[w].toString()
                'F' -> javaFloatToString(Float.fromBits(t.p[w].toInt()))
                'D' -> javaDoubleToString(Double.fromBits(t.p[w]))
                else -> vm.vmToString(t.r[w])
            }
            w += wordsOf(kindOf(d[0]))
        }
        val out = StringBuilder()
        if (recipe == null) values.forEach { out.append(it) }
        else {
            var arg = 0
            var constant = 0
            for (ch in recipe) {
                when (ch) {
                    '\u0001' -> out.append(values[arg++])
                    '\u0002' -> out.append(vm.vmToString(constants[constant++]))
                    else -> out.append(ch)
                }
            }
        }
        t.r[base] = out.toString()
        return base + 1
    }
}
