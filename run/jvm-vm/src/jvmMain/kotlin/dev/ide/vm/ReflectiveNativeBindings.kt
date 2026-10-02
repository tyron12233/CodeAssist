package dev.ide.vm

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * [HostBindings] for a host that IS a JVM: a `native` method of an interpreted library resolves to the same
 * method of the real class, loaded by [loader], so its JNI implementation runs. This is how the VM is tested
 * against real skia on the desktop; iOS binds the same methods to C symbols instead.
 *
 * Arguments cross as they are where they can (a `String`, a `float[]`: the VM's values are the JVM's) and are
 * converted where they cannot: an interpreted lambda becomes a real `Function0` that calls back into the VM,
 * an interpreted array of references becomes a real array. A result that is a library object (skia's
 * paragraph metrics) is copied field by field into an instance of the same, interpreted, class.
 */
class ReflectiveNativeBindings(
    private val loader: ClassLoader,
    private val prefixes: List<String> = listOf("org/jetbrains/skia/", "org/jetbrains/skiko/"),
    /** Called once before the first binding, e.g. to load the real native library into this JVM. */
    private val prepare: () -> Unit = {},
) : HostBindings {
    private var prepared = false

    override fun override(vm: Vm, owner: String, name: String, descriptor: String): Native? =
        Skiko.override(vm, owner, name, descriptor)

    override fun native(vm: Vm, owner: String, name: String, descriptor: String): Native? {
        if (prefixes.none { owner.startsWith(it) }) return null
        if (!prepared) { prepared = true; prepare() }
        val cls = Class.forName(owner.replace('/', '.'), true, loader)
        val params = parameterDescriptors(descriptor)
        val method: Method = cls.getDeclaredMethod(name, *params.map { classFor(it) }.toTypedArray())
        method.isAccessible = true
        val static = Modifier.isStatic(method.modifiers)
        val receiver: Any? = if (static) null else hostInstance(cls)
        val ret = returnDescriptor(descriptor)
        return Native { call ->
            val args = arrayOfNulls<Any?>(params.size)
            var w = if (static) 0 else 1
            for (k in params.indices) {
                val d = params[k]
                args[k] = when (d[0]) {
                    'I' -> call.i(w)
                    'Z' -> call.z(w)
                    'B' -> call.i(w).toByte()
                    'S' -> call.i(w).toShort()
                    'C' -> call.c(w)
                    'J' -> call.l(w)
                    'F' -> call.f(w)
                    'D' -> call.d(w)
                    else -> toHost(vm, call.r(w))
                }
                w += if (d == "J" || d == "D") 2 else 1
            }
            val result = try {
                method.invoke(receiver, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
            when (ret[0]) {
                'V' -> {}
                'Z' -> call.ret(result as Boolean)
                'I' -> call.ret(result as Int)
                'B' -> call.ret((result as Byte).toInt())
                'S' -> call.ret((result as Short).toInt())
                'C' -> call.ret(result as Char)
                'J' -> call.ret(result as Long)
                'F' -> call.ret(result as Float)
                'D' -> call.ret(result as Double)
                else -> call.retRef(fromHost(vm, result))
            }
        }
    }

    private fun hostInstance(cls: Class<*>): Any {
        runCatching { return cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) }
        val outer = cls.enclosingClass ?: error("no instance for ${cls.name}")
        val f = outer.declaredFields.first { it.type == cls && Modifier.isStatic(it.modifiers) }
        f.isAccessible = true
        return f.get(null)
    }

    private fun classFor(d: String): Class<*> = when (d) {
        "I" -> Int::class.javaPrimitiveType!!
        "J" -> Long::class.javaPrimitiveType!!
        "Z" -> Boolean::class.javaPrimitiveType!!
        "F" -> Float::class.javaPrimitiveType!!
        "D" -> Double::class.javaPrimitiveType!!
        "B" -> Byte::class.javaPrimitiveType!!
        "S" -> Short::class.javaPrimitiveType!!
        "C" -> Char::class.javaPrimitiveType!!
        else -> Class.forName(if (d[0] == 'L') d.substring(1, d.length - 1).replace('/', '.') else d.replace('/', '.'), false, loader)
    }

    private fun toHost(vm: Vm, v: Any?): Any? = when (v) {
        null, is String, is IntArray, is FloatArray, is ByteArray, is ShortArray, is LongArray,
        is DoubleArray, is CharArray, is BooleanArray -> v
        is VmRefArray -> {
            val items = v.data.map { toHost(vm, it) }
            if (items.all { it is String }) items.map { it as String }.toTypedArray() else items.toTypedArray()
        }
        is VmObject -> when {
            vm.isInstance(v, vm.loadClass("kotlin/jvm/functions/Function0")) -> object : Function0<Any?> {
                override fun invoke(): Any? = vm.callVirtual(v, "invoke", "()Ljava/lang/Object;")
            }
            else -> throw VmUnsupportedException("cannot pass ${v.cls.name} to a host native")
        }
        else -> v
    }

    private fun fromHost(vm: Vm, v: Any?): Any? = when (v) {
        null, is String, is IntArray, is FloatArray, is ByteArray, is ShortArray, is LongArray,
        is DoubleArray, is CharArray, is BooleanArray, is Int, is Long, is Float, is Double, is Boolean -> v
        is Array<*> -> {
            val component = v.javaClass.componentType.name.replace('.', '/')
            vm.newRefArray(component, Array(v.size) { fromHost(vm, v[it]) })
        }
        else -> copyIntoVm(vm, v)
    }

    /** A host object of a library class, rebuilt as an instance of the same interpreted class. An enum value
     *  is the interpreted class's constant of the same name, not a copy, so identity comparisons still hold. */
    private fun copyIntoVm(vm: Vm, v: Any): Any? {
        if (v is Enum<*>) {
            val enumClass = vm.loadClass(v.declaringJavaClass.name.replace('.', '/'))
            return vm.enumConstantNamed(enumClass, v.name)
        }
        val cls = vm.loadClass(v.javaClass.name.replace('.', '/'))
        vm.ensureInitialized(cls)
        val o = VmObject(cls)
        var c: Class<*>? = v.javaClass
        while (c != null && c != Any::class.java && !c.name.startsWith("java.")) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val vf = vm.findField(cls, f.name, descriptorOf(f.type)) ?: continue
                val value = f.get(v)
                if (vf.isRef) o.refs[vf.slot] = fromHost(vm, value)
                else o.prims[vf.slot] = when (value) {
                    is Int -> value.toLong(); is Long -> value; is Boolean -> if (value) 1L else 0L
                    is Float -> value.toRawBits().toLong(); is Double -> value.toRawBits()
                    is Char -> value.code.toLong(); is Byte -> value.toLong(); is Short -> value.toLong()
                    else -> 0L
                }
            }
            c = c.superclass
        }
        return o
    }

    private fun descriptorOf(t: Class<*>): String = when {
        t == Int::class.javaPrimitiveType -> "I"
        t == Long::class.javaPrimitiveType -> "J"
        t == Boolean::class.javaPrimitiveType -> "Z"
        t == Float::class.javaPrimitiveType -> "F"
        t == Double::class.javaPrimitiveType -> "D"
        t == Byte::class.javaPrimitiveType -> "B"
        t == Short::class.javaPrimitiveType -> "S"
        t == Char::class.javaPrimitiveType -> "C"
        t.isArray -> t.name.replace('.', '/')
        else -> "L" + t.name.replace('.', '/') + ";"
    }
}
