package dev.ide.jvm

/**
 * Conversions between the interpreter's value representation and real values crossing the [NativeBridge] or a
 * peer boundary. Computational-int types move between a Kotlin [Int] and the real primitive wrappers; a peer
 * handed back from platform code is resolved to the interpreted instance it stands for.
 */
internal object Marshalling {
    /** Convert a real value in a REFERENCE position into the interpreter's representation: a peer is resolved
     *  to the interpreted instance it stands for. A boxed Boolean/Char/Byte/Short is a real OBJECT here and
     *  passes through unchanged — flattening it to the computational form would break reference-typed uses
     *  (`checkcast java/lang/Boolean`, unboxing calls) on the interpreted side. */
    fun realToVm(value: Any?): Any? = if (value is VmPeer) value.vmObject() else value

    /** Convert a real value in a statically PRIMITIVE position into the interpreter's computational form
     *  (boolean/byte/char/short become Int). Only safe where the static type says primitive. */
    fun realPrimToVm(value: Any?): Any? = when (value) {
        is Boolean -> if (value) 1 else 0
        is Char -> value.code
        is Byte -> value.toInt()
        is Short -> value.toInt()
        else -> value
    }

    /** Convert by [descriptor]: a primitive descriptor takes the computational form, a reference unwraps peers. */
    fun realToVm(value: Any?, descriptor: String): Any? =
        if (descriptor.length == 1) realPrimToVm(value) else realToVm(value)

    /** Convert an interpreter value to a real value for a method result of [returnDescriptor]. */
    fun vmToReal(value: Any?, returnDescriptor: String): Any? = when (returnDescriptor) {
        "V" -> null
        "Z" -> (value as Int) != 0
        "C" -> (value as Int).toChar()
        "B" -> (value as Int).toByte()
        "S" -> (value as Int).toShort()
        else -> value
    }

    /** Convert an interpreter value to the real wrapper a parameter or array element of [descriptor] expects.
     *  References (including an already-converted peer or real array) pass through. */
    fun toRealArg(value: Any?, descriptor: String): Any? = when (descriptor) {
        "Z" -> (value as Int) != 0
        "B" -> (value as Int).toByte()
        "C" -> (value as Int).toChar()
        "S" -> (value as Int).toShort()
        else -> value
    }
}
