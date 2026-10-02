@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ide.vm.skiko

import dev.ide.vm.Call
import dev.ide.vm.HostBindings
import dev.ide.vm.Native
import dev.ide.vm.Skiko
import dev.ide.vm.Vm
import dev.ide.vm.VmObject
import dev.ide.vm.VmRefArray
import dev.ide.vm.VmUnsupportedException
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.toLong
import dev.ide.vm.skiko.bridges.skikoBridgeLookup

/**
 * [HostBindings] for iOS: a skia `native` method of the interpreted (JVM) skiko resolves to the C function
 * skiko's own iOS build exports for it (`CanvasKt._nDrawRect` is `org_jetbrains_skia_Canvas__1nDrawRect`),
 * looked up by name in the generated bridge table and called through a [callSkia] shape.
 *
 * Both builds of skiko call the same C++, but they hand it arguments differently: the JVM build passes a
 * `String` or a `float[]` as the object itself (JNI reads it), the native build passes a pointer to UTF-8
 * bytes or to the array's elements. So each reference argument is marshalled here the way skiko's native
 * `InteropScope` would have: pinned for the length of the call, so C writes into a result array land in the
 * interpreted program's own array.
 *
 * The table (`skikoBridges.def`) references every bridge, which is also what keeps them in the link:
 * nothing else in the host calls most of them.
 */
class SkikoNativeBindings : HostBindings {
    init {
        // skiko's native Native companion registers the C-to-Kotlin callback trampolines a callback argument
        // needs (see marshal); touching it runs that registration.
        org.jetbrains.skia.impl.Native.NullPointer
    }

    override fun override(vm: Vm, owner: String, name: String, descriptor: String): Native? =
        Skiko.override(vm, owner, name, descriptor)

    override fun native(vm: Vm, owner: String, name: String, descriptor: String): Native? {
        if (!owner.startsWith("org/jetbrains/skia") && !owner.startsWith("org/jetbrains/skiko")) return null
        val candidates = symbolCandidates(owner, name)
        val fn: COpaquePointer = candidates.firstNotNullOfOrNull { skikoBridgeLookup(it) }
            ?: throw VmUnsupportedException("no C symbol for $owner.$name$descriptor (tried ${candidates.joinToString()})")
        val params = parameterDescriptors(descriptor)
        val ret = descriptor.substring(descriptor.indexOf(')') + 1)
        val key = params.joinToString("") { abi(it) } + ">" + (if (ret == "V") "V" else abi(ret))
        val shape = skiaShapes[key] ?: throw VmUnsupportedException("no C call shape $key for $owner.$name")
        val decoder = RESULT_DECODERS["$owner.$name"]
        val firstWord = if (isInstanceNative(owner)) 1 else 0
        return Native { call ->
            val a = LongArray(params.size)
            val pins = ArrayList<Pinned<*>>(2)
            var w = firstWord
            for (k in params.indices) {
                val d = params[k]
                a[k] = when (d[0]) {
                    'I', 'Z', 'B', 'S', 'C' -> call.i(w).toLong()
                    'J' -> call.l(w)
                    'F' -> call.f(w).toRawBits().toLong()
                    'D' -> call.d(w).toRawBits()
                    else -> marshal(vm, call.r(w), pins)
                }
                w += if (d == "J" || d == "D") 2 else 1
            }
            val result = try {
                callSkia(shape, fn, a)
            } finally {
                for (p in pins) p.unpin()
            }
            when (ret[0]) {
                'V' -> {}
                'Z' -> call.ret(result and 0xFF != 0L)
                'B' -> call.ret(result.toInt().toByte().toInt())
                'S' -> call.ret(result.toInt().toShort().toInt())
                'C' -> call.ret((result.toInt() and 0xFFFF).toChar())
                'I' -> call.ret(result.toInt())
                'J' -> call.ret(result)
                'F' -> call.ret(Float.fromBits(result.toInt()))
                'D' -> call.ret(Double.fromBits(result))
                else -> call.retRef(if (decoder != null) decode(vm, decoder, result) else result)
            }
        }
    }

    /**
     * A reference argument as the native build of skiko passes it: null as 0, a string as zero-terminated
     * UTF-8, a primitive array as its elements (0 when empty), an array of strings or interop values as an
     * array of pointers, a lambda as a StableRef skiko's trampolines call back through, and a [Long] (a
     * pointer an earlier native returned as an interop value) as itself.
     */
    private fun marshal(vm: Vm, v: Any?, pins: MutableList<Pinned<*>>): Long = when (v) {
        null -> 0L
        is Long -> v
        is String -> (v.encodeToByteArray() + 0).pin().also { pins.add(it) }.addressOf(0).toLong()
        is ByteArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is ShortArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is IntArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is LongArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is FloatArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is DoubleArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is CharArray -> if (v.isEmpty()) 0L else v.pin().also { pins.add(it) }.addressOf(0).toLong()
        is VmRefArray -> if (v.data.isEmpty()) 0L else {
            val pointers = LongArray(v.data.size) { marshal(vm, v.data[it], pins) }
            pointers.pin().also { pins.add(it) }.addressOf(0).toLong()
        }
        is VmObject -> {
            if (!vm.isFunction0(v)) throw VmUnsupportedException("cannot pass ${v.cls.name} to a skia native")
            val callback: () -> Any? = { vm.invokeFunction0(v) }
            StableRef.create(callback).asCPointer().toLong()
        }
        else -> throw VmUnsupportedException("cannot pass ${v::class} to a skia native")
    }

    /** An array result (paragraph line metrics, text boxes) rebuilt with skiko's own interpreted decoder. */
    private fun decode(vm: Vm, elementClass: String, pointer: Long): Any? {
        if (pointer == 0L) return vm.newRefArray(elementClass, emptyArray())
        val companion = vm.companionOf(elementClass)
        val size = vm.invokeVirtualPublic(companion, "getArraySize", "(Ljava/lang/Object;)I", pointer) as Int
        val items = Array<Any?>(size) { vm.invokeVirtualPublic(companion, "getArrayElement", "(Ljava/lang/Object;I)L$elementClass;", pointer, it) }
        vm.invokeVirtualPublic(companion, "disposeArray", "(Ljava/lang/Object;)V", pointer)
        return vm.newRefArray(elementClass, items)
    }

    private fun isInstanceNative(owner: String): Boolean = owner.endsWith("\$Companion")

    private fun abi(d: String): String = when (d[0]) {
        'I', 'Z', 'B', 'S', 'C' -> "I"
        'F' -> "F"
        'D' -> "D"
        else -> "J"
    }

    private companion object {
        /** Natives whose JVM build returns objects JNI constructs, and the class whose decoder rebuilds them. */
        val RESULT_DECODERS = mapOf(
            "org/jetbrains/skia/paragraph/ParagraphKt._nGetLineMetrics" to "org/jetbrains/skia/paragraph/LineMetrics",
            "org/jetbrains/skia/paragraph/ParagraphKt._nGetRectsForRange" to "org/jetbrains/skia/paragraph/TextBox",
            "org/jetbrains/skia/paragraph/ParagraphKt._nGetRectsForPlaceholders" to "org/jetbrains/skia/paragraph/TextBox",
        )

        /**
         * The C names a JVM native may have. skiko names its C bridges after the Kotlin declaration, and the
         * JVM name differs from it in a few regular ways: a file facade's `Kt` suffix, `X_nY` functions
         * declared at top level, a companion's `$Companion`.
         */
        fun symbolCandidates(owner: String, name: String): List<String> {
            val pkg = owner.substringBeforeLast('/').replace('/', '_')
            val cls = owner.substringAfterLast('/').substringBefore('$')
            val bare = cls.removeSuffix("Kt").removeSuffix("_jvm")
            val escaped = name.replace("_", "_1")
            val out = ArrayList<String>(6)
            out.add("${pkg}_${bare}_$escaped")
            out.add("${pkg}_${cls}_$escaped")
            val split = Regex("(\\w+?)_n(\\w+)").matchEntire(name)
            if (split != null) {
                val (a, b) = split.destructured
                out.add("${pkg}_${a}__1n$b")
                out.add("${pkg}_${bare}__1n${a}_$b")
                out.add("${pkg}_${bare}__1${a}_n$b")
                out.add("${pkg}_${bare}_${a}__1n$b")
                out.add("${pkg}_$name")
            }
            if (name.startsWith("_n") && name.length > 2) {
                val rest = name.substring(2)
                out.add("${pkg}_${bare}__${rest[0].lowercaseChar()}${rest.substring(1)}")
                out.add("${pkg}_${bare}__n$rest")
            }
            return out
        }

        fun parameterDescriptors(descriptor: String): List<String> {
            val out = ArrayList<String>(4)
            var i = 1
            while (descriptor[i] != ')') {
                val start = i
                while (descriptor[i] == '[') i++
                i = if (descriptor[i] == 'L') descriptor.indexOf(';', i) + 1 else i + 1
                out.add(descriptor.substring(start, i))
            }
            return out
        }
    }
}
