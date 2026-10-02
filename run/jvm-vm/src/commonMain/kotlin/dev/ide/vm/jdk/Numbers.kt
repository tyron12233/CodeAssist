package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.Vm
import dev.ide.vm.define
import dev.ide.vm.javaDoubleToString
import dev.ide.vm.javaFloatToString
import dev.ide.vm.setStaticRef

/**
 * The boxes. Each is the Kotlin box of the same type (an `Integer` is an [Int]), so `Integer.valueOf(1)`
 * and Kotlin's own boxing agree, and a box crossing to host code needs no conversion.
 */
internal fun ClassDefs.registerNumbers() {
    define("java/lang/Number") {
        asAbstract()
        implements("java/io/Serializable")
        ctor("()V") {}
        method("byteValue", "()B") { ret((vm.callVirtual(r(0), "intValue", "()I") as Int).toByte().toInt()) }
        method("shortValue", "()S") { ret((vm.callVirtual(r(0), "intValue", "()I") as Int).toShort().toInt()) }
    }

    define("java/lang/Integer") {
        box("I", "int")
        ctor("(I)V") { retRef(i(1)) }
        ctor("(Ljava/lang/String;)V") { retRef(parseInt(vm, r(1) as String?, 10)) }
        numberValues { (it as Int).toLong() }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Int && r(0) == r(1)) }
        method("hashCode", "()I") { ret(r(0) as Int) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Int).toString()) }
        method("compareTo", "(Ljava/lang/Integer;)I") { ret((r(0) as Int).compareTo(r(1) as Int)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Int).compareTo(r(1) as Int)) }
        static("valueOf", "(I)Ljava/lang/Integer;") { retRef(i(0)) }
        static("valueOf", "(Ljava/lang/String;)Ljava/lang/Integer;") { retRef(parseInt(vm, r(0) as String?, 10)) }
        static("valueOf", "(Ljava/lang/String;I)Ljava/lang/Integer;") { retRef(parseInt(vm, r(0) as String?, i(1))) }
        static("parseInt", "(Ljava/lang/String;)I") { ret(parseInt(vm, r(0) as String?, 10)) }
        static("parseInt", "(Ljava/lang/String;I)I") { ret(parseInt(vm, r(0) as String?, i(1))) }
        static("parseUnsignedInt", "(Ljava/lang/String;)I") { ret((r(0) as String).toUIntOrNull()?.toInt() ?: numberFormat(vm, r(0))) }
        static("decode", "(Ljava/lang/String;)Ljava/lang/Integer;") { retRef(decode(vm, r(0) as String).toInt()) }
        static("toString", "(I)Ljava/lang/String;") { retRef(i(0).toString()) }
        static("toString", "(II)Ljava/lang/String;") { retRef(i(0).toString(i(1).let { if (it in 2..36) it else 10 })) }
        static("toHexString", "(I)Ljava/lang/String;") { retRef(i(0).toUInt().toString(16)) }
        static("toOctalString", "(I)Ljava/lang/String;") { retRef(i(0).toUInt().toString(8)) }
        static("toBinaryString", "(I)Ljava/lang/String;") { retRef(i(0).toUInt().toString(2)) }
        static("toUnsignedString", "(I)Ljava/lang/String;") { retRef(i(0).toUInt().toString()) }
        static("toUnsignedLong", "(I)J") { ret(i(0).toLong() and 0xFFFFFFFFL) }
        static("compare", "(II)I") { ret(i(0).compareTo(i(1))) }
        static("compareUnsigned", "(II)I") { ret(i(0).toUInt().compareTo(i(1).toUInt())) }
        static("divideUnsigned", "(II)I") { ret((i(0).toUInt() / i(1).toUInt()).toInt()) }
        static("remainderUnsigned", "(II)I") { ret((i(0).toUInt() % i(1).toUInt()).toInt()) }
        static("hashCode", "(I)I") { ret(i(0)) }
        static("signum", "(I)I") { ret(i(0).let { if (it > 0) 1 else if (it < 0) -1 else 0 }) }
        static("bitCount", "(I)I") { ret(i(0).countOneBits()) }
        static("numberOfLeadingZeros", "(I)I") { ret(i(0).countLeadingZeroBits()) }
        static("numberOfTrailingZeros", "(I)I") { ret(i(0).countTrailingZeroBits()) }
        static("highestOneBit", "(I)I") { ret(i(0).takeHighestOneBit()) }
        static("lowestOneBit", "(I)I") { ret(i(0).takeLowestOneBit()) }
        static("rotateLeft", "(II)I") { ret(i(0).rotateLeft(i(1))) }
        static("rotateRight", "(II)I") { ret(i(0).rotateRight(i(1))) }
        static("reverse", "(I)I") { ret(reverseBits(i(0))) }
        static("reverseBytes", "(I)I") { val v = i(0); ret((v shl 24) or ((v and 0xFF00) shl 8) or ((v ushr 8) and 0xFF00) or (v ushr 24)) }
        static("max", "(II)I") { ret(maxOf(i(0), i(1))) }
        static("min", "(II)I") { ret(minOf(i(0), i(1))) }
        static("sum", "(II)I") { ret(i(0) + i(1)) }
    }

    define("java/lang/Long") {
        box("J", "long")
        ctor("(J)V") { retRef(l(1)) }
        numberValues { it as Long }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Long && r(0) == r(1)) }
        method("hashCode", "()I") { val v = r(0) as Long; ret((v xor (v ushr 32)).toInt()) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Long).toString()) }
        method("compareTo", "(Ljava/lang/Long;)I") { ret((r(0) as Long).compareTo(r(1) as Long)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Long).compareTo(r(1) as Long)) }
        static("valueOf", "(J)Ljava/lang/Long;") { retRef(l(0)) }
        static("valueOf", "(Ljava/lang/String;)Ljava/lang/Long;") { retRef(parseLong(vm, r(0) as String?, 10)) }
        static("parseLong", "(Ljava/lang/String;)J") { ret(parseLong(vm, r(0) as String?, 10)) }
        static("parseLong", "(Ljava/lang/String;I)J") { ret(parseLong(vm, r(0) as String?, i(1))) }
        static("decode", "(Ljava/lang/String;)Ljava/lang/Long;") { retRef(decode(vm, r(0) as String)) }
        static("toString", "(J)Ljava/lang/String;") { retRef(l(0).toString()) }
        static("toString", "(JI)Ljava/lang/String;") { retRef(l(0).toString(i(2).let { if (it in 2..36) it else 10 })) }
        static("toHexString", "(J)Ljava/lang/String;") { retRef(l(0).toULong().toString(16)) }
        static("toOctalString", "(J)Ljava/lang/String;") { retRef(l(0).toULong().toString(8)) }
        static("toBinaryString", "(J)Ljava/lang/String;") { retRef(l(0).toULong().toString(2)) }
        static("toUnsignedString", "(J)Ljava/lang/String;") { retRef(l(0).toULong().toString()) }
        static("compare", "(JJ)I") { ret(l(0).compareTo(l(2))) }
        static("compareUnsigned", "(JJ)I") { ret(l(0).toULong().compareTo(l(2).toULong())) }
        static("divideUnsigned", "(JJ)J") { ret((l(0).toULong() / l(2).toULong()).toLong()) }
        static("remainderUnsigned", "(JJ)J") { ret((l(0).toULong() % l(2).toULong()).toLong()) }
        static("hashCode", "(J)I") { val v = l(0); ret((v xor (v ushr 32)).toInt()) }
        static("signum", "(J)I") { ret(l(0).let { if (it > 0) 1 else if (it < 0) -1 else 0 }) }
        static("bitCount", "(J)I") { ret(l(0).countOneBits()) }
        static("numberOfLeadingZeros", "(J)I") { ret(l(0).countLeadingZeroBits()) }
        static("numberOfTrailingZeros", "(J)I") { ret(l(0).countTrailingZeroBits()) }
        static("highestOneBit", "(J)J") { ret(l(0).takeHighestOneBit()) }
        static("lowestOneBit", "(J)J") { ret(l(0).takeLowestOneBit()) }
        static("rotateLeft", "(JI)J") { ret(l(0).rotateLeft(i(2))) }
        static("rotateRight", "(JI)J") { ret(l(0).rotateRight(i(2))) }
        static("reverse", "(J)J") { val v = l(0); ret((reverseBits(v.toInt()).toLong() shl 32) or (reverseBits((v ushr 32).toInt()).toLong() and 0xFFFFFFFFL)) }
        static("reverseBytes", "(J)J") {
            var v = l(0); var out = 0L
            repeat(8) { out = (out shl 8) or (v and 0xFF); v = v ushr 8 }
            ret(out)
        }
        static("max", "(JJ)J") { ret(maxOf(l(0), l(2))) }
        static("min", "(JJ)J") { ret(minOf(l(0), l(2))) }
        static("sum", "(JJ)J") { ret(l(0) + l(2)) }
    }

    define("java/lang/Short") {
        box("S", "short")
        ctor("(S)V") { retRef(i(1).toShort()) }
        numberValues { (it as Short).toLong() }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Short && r(0) == r(1)) }
        method("hashCode", "()I") { ret((r(0) as Short).toInt()) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Short).toString()) }
        method("compareTo", "(Ljava/lang/Short;)I") { ret((r(0) as Short) - (r(1) as Short)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Short) - (r(1) as Short)) }
        static("valueOf", "(S)Ljava/lang/Short;") { retRef(i(0).toShort()) }
        static("parseShort", "(Ljava/lang/String;)S") { ret(parseInt(vm, r(0) as String?, 10).toShort().toInt()) }
        static("toString", "(S)Ljava/lang/String;") { retRef(i(0).toString()) }
        static("hashCode", "(S)I") { ret(i(0)) }
        static("compare", "(SS)I") { ret(i(0) - i(1)) }
        static("toUnsignedInt", "(S)I") { ret(i(0) and 0xFFFF) }
        static("reverseBytes", "(S)S") { val v = i(0); ret((((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)).toShort().toInt()) }
    }

    define("java/lang/Byte") {
        box("B", "byte")
        ctor("(B)V") { retRef(i(1).toByte()) }
        numberValues { (it as Byte).toLong() }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Byte && r(0) == r(1)) }
        method("hashCode", "()I") { ret((r(0) as Byte).toInt()) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Byte).toString()) }
        method("compareTo", "(Ljava/lang/Byte;)I") { ret((r(0) as Byte) - (r(1) as Byte)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Byte) - (r(1) as Byte)) }
        static("valueOf", "(B)Ljava/lang/Byte;") { retRef(i(0).toByte()) }
        static("parseByte", "(Ljava/lang/String;)B") { ret(parseInt(vm, r(0) as String?, 10).toByte().toInt()) }
        static("toString", "(B)Ljava/lang/String;") { retRef(i(0).toString()) }
        static("hashCode", "(B)I") { ret(i(0)) }
        static("compare", "(BB)I") { ret(i(0) - i(1)) }
        static("toUnsignedInt", "(B)I") { ret(i(0) and 0xFF) }
        static("toUnsignedLong", "(B)J") { ret(i(0).toLong() and 0xFF) }
    }

    define("java/lang/Boolean") {
        valueBacked()
        implements("java/io/Serializable", "java/lang/Comparable", "java/lang/constant/Constable")
        staticField("TRUE", "Ljava/lang/Boolean;")
        staticField("FALSE", "Ljava/lang/Boolean;")
        staticField("TYPE", "Ljava/lang/Class;")
        onInit {
            it.setStaticRef("TRUE", "Ljava/lang/Boolean;", true)
            it.setStaticRef("FALSE", "Ljava/lang/Boolean;", false)
            it.setStaticRef("TYPE", "Ljava/lang/Class;", vm.mirror(vm.primitiveClass('Z')))
        }
        ctor("(Z)V") { retRef(z(1)) }
        method("booleanValue", "()Z") { ret(r(0) as Boolean) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is Boolean && r(0) == r(1)) }
        method("hashCode", "()I") { ret(if (r(0) as Boolean) 1231 else 1237) }
        method("toString", "()Ljava/lang/String;") { retRef((r(0) as Boolean).toString()) }
        method("compareTo", "(Ljava/lang/Boolean;)I") { ret((r(0) as Boolean).compareTo(r(1) as Boolean)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Boolean).compareTo(r(1) as Boolean)) }
        static("valueOf", "(Z)Ljava/lang/Boolean;") { retRef(z(0)) }
        static("valueOf", "(Ljava/lang/String;)Ljava/lang/Boolean;") { retRef((r(0) as String?).equals("true", ignoreCase = true)) }
        static("parseBoolean", "(Ljava/lang/String;)Z") { ret((r(0) as String?).equals("true", ignoreCase = true)) }
        static("toString", "(Z)Ljava/lang/String;") { retRef(z(0).toString()) }
        static("hashCode", "(Z)I") { ret(if (z(0)) 1231 else 1237) }
        static("compare", "(ZZ)I") { ret(z(0).compareTo(z(1))) }
        static("logicalAnd", "(ZZ)Z") { ret(z(0) && z(1)) }
        static("logicalOr", "(ZZ)Z") { ret(z(0) || z(1)) }
        static("logicalXor", "(ZZ)Z") { ret(z(0) xor z(1)) }
    }

    define("java/lang/Float") {
        box("F", "float")
        ctor("(F)V") { retRef(f(1)) }
        ctor("(D)V") { retRef(d(1).toFloat()) }
        method("intValue", "()I") { ret((r(0) as Float).toInt()) }
        method("longValue", "()J") { ret((r(0) as Float).toLong()) }
        method("floatValue", "()F") { ret(r(0) as Float) }
        method("doubleValue", "()D") { ret((r(0) as Float).toDouble()) }
        method("byteValue", "()B") { ret((r(0) as Float).toInt().toByte().toInt()) }
        method("shortValue", "()S") { ret((r(0) as Float).toInt().toShort().toInt()) }
        method("isNaN", "()Z") { ret((r(0) as Float).isNaN()) }
        method("isInfinite", "()Z") { ret((r(0) as Float).isInfinite()) }
        method("equals", "(Ljava/lang/Object;)Z") { val o = r(1); ret(o is Float && (r(0) as Float).toBits() == o.toBits()) }
        method("hashCode", "()I") { ret((r(0) as Float).toBits()) }
        method("toString", "()Ljava/lang/String;") { retRef(javaFloatToString(r(0) as Float)) }
        method("compareTo", "(Ljava/lang/Float;)I") { ret((r(0) as Float).compareTo(r(1) as Float)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Float).compareTo(r(1) as Float)) }
        static("valueOf", "(F)Ljava/lang/Float;") { retRef(f(0)) }
        static("valueOf", "(Ljava/lang/String;)Ljava/lang/Float;") { retRef(parseDouble(vm, r(0) as String?).toFloat()) }
        static("parseFloat", "(Ljava/lang/String;)F") { ret(parseDouble(vm, r(0) as String?).toFloat()) }
        static("toString", "(F)Ljava/lang/String;") { retRef(javaFloatToString(f(0))) }
        static("floatToRawIntBits", "(F)I") { ret(f(0).toRawBits()) }
        static("floatToIntBits", "(F)I") { ret(f(0).toBits()) }
        static("intBitsToFloat", "(I)F") { ret(Float.fromBits(i(0))) }
        static("isNaN", "(F)Z") { ret(f(0).isNaN()) }
        static("isInfinite", "(F)Z") { ret(f(0).isInfinite()) }
        static("isFinite", "(F)Z") { ret(f(0).isFinite()) }
        static("compare", "(FF)I") { ret(f(0).compareTo(f(1))) }
        static("hashCode", "(F)I") { ret(f(0).toBits()) }
        static("max", "(FF)F") { ret(maxOf(f(0), f(1))) }
        static("min", "(FF)F") { ret(minOf(f(0), f(1))) }
        static("sum", "(FF)F") { ret(f(0) + f(1)) }
    }

    define("java/lang/Double") {
        box("D", "double")
        ctor("(D)V") { retRef(d(1)) }
        method("intValue", "()I") { ret((r(0) as Double).toInt()) }
        method("longValue", "()J") { ret((r(0) as Double).toLong()) }
        method("floatValue", "()F") { ret((r(0) as Double).toFloat()) }
        method("doubleValue", "()D") { ret(r(0) as Double) }
        method("byteValue", "()B") { ret((r(0) as Double).toInt().toByte().toInt()) }
        method("shortValue", "()S") { ret((r(0) as Double).toInt().toShort().toInt()) }
        method("isNaN", "()Z") { ret((r(0) as Double).isNaN()) }
        method("isInfinite", "()Z") { ret((r(0) as Double).isInfinite()) }
        method("equals", "(Ljava/lang/Object;)Z") { val o = r(1); ret(o is Double && (r(0) as Double).toBits() == o.toBits()) }
        method("hashCode", "()I") { val b = (r(0) as Double).toBits(); ret((b xor (b ushr 32)).toInt()) }
        method("toString", "()Ljava/lang/String;") { retRef(javaDoubleToString(r(0) as Double)) }
        method("compareTo", "(Ljava/lang/Double;)I") { ret((r(0) as Double).compareTo(r(1) as Double)) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret((r(0) as Double).compareTo(r(1) as Double)) }
        static("valueOf", "(D)Ljava/lang/Double;") { retRef(d(0)) }
        static("valueOf", "(Ljava/lang/String;)Ljava/lang/Double;") { retRef(parseDouble(vm, r(0) as String?)) }
        static("parseDouble", "(Ljava/lang/String;)D") { ret(parseDouble(vm, r(0) as String?)) }
        static("toString", "(D)Ljava/lang/String;") { retRef(javaDoubleToString(d(0))) }
        static("doubleToRawLongBits", "(D)J") { ret(d(0).toRawBits()) }
        static("doubleToLongBits", "(D)J") { ret(d(0).toBits()) }
        static("longBitsToDouble", "(J)D") { ret(Double.fromBits(l(0))) }
        static("isNaN", "(D)Z") { ret(d(0).isNaN()) }
        static("isInfinite", "(D)Z") { ret(d(0).isInfinite()) }
        static("isFinite", "(D)Z") { ret(d(0).isFinite()) }
        static("compare", "(DD)I") { ret(d(0).compareTo(d(2))) }
        static("hashCode", "(D)I") { val b = d(0).toBits(); ret((b xor (b ushr 32)).toInt()) }
        static("max", "(DD)D") { ret(maxOf(d(0), d(2))) }
        static("min", "(DD)D") { ret(minOf(d(0), d(2))) }
        static("sum", "(DD)D") { ret(d(0) + d(2)) }
    }
}

private fun NativeClassBuilder.box(descriptor: String, primitive: String) {
    superName = "java/lang/Number"
    valueBacked()
    implements("java/lang/Comparable", "java/lang/constant/Constable")
    staticField("TYPE", "Ljava/lang/Class;")
    onInit { it.setStaticRef("TYPE", "Ljava/lang/Class;", vm.mirror(vm.primitiveClass(descriptor[0]))) }
}

/** `intValue`/`longValue`/... for an integral box, from its value as a [Long]. */
private fun NativeClassBuilder.numberValues(asLong: (Any) -> Long) {
    method("intValue", "()I") { ret(asLong(r(0)!!).toInt()) }
    method("longValue", "()J") { ret(asLong(r(0)!!)) }
    method("floatValue", "()F") { ret(asLong(r(0)!!).toFloat()) }
    method("doubleValue", "()D") { ret(asLong(r(0)!!).toDouble()) }
    method("byteValue", "()B") { ret(asLong(r(0)!!).toInt().toByte().toInt()) }
    method("shortValue", "()S") { ret(asLong(r(0)!!).toInt().toShort().toInt()) }
}

private fun reverseBits(v: Int): Int {
    var x = v
    var out = 0
    repeat(32) { out = (out shl 1) or (x and 1); x = x ushr 1 }
    return out
}

private fun numberFormat(vm: Vm, s: Any?): Nothing =
    vm.throwVm("java/lang/NumberFormatException", if (s == null) "Cannot parse null string: null" else "For input string: \"$s\"")

private fun parseInt(vm: Vm, s: String?, radix: Int): Int = s?.toIntOrNull(radix) ?: numberFormat(vm, s)
private fun parseLong(vm: Vm, s: String?, radix: Int): Long = s?.toLongOrNull(radix) ?: numberFormat(vm, s)

private fun parseDouble(vm: Vm, s: String?): Double {
    if (s == null) vm.throwVm("java/lang/NullPointerException", null)
    val t = s.trim().removeSuffix("f").removeSuffix("F").removeSuffix("d").removeSuffix("D")
    return t.toDoubleOrNull() ?: numberFormat(vm, s)
}

private fun decode(vm: Vm, s: String): Long {
    var t = s
    var neg = false
    if (t.startsWith("-")) { neg = true; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
    val v = when {
        t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLongOrNull(16)
        t.startsWith("#") -> t.substring(1).toLongOrNull(16)
        t.startsWith("0") && t.length > 1 -> t.substring(1).toLongOrNull(8)
        else -> t.toLongOrNull()
    } ?: numberFormat(vm, s)
    return if (neg) -v else v
}
