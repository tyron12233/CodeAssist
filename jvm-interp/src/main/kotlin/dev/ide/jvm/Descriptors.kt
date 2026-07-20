package dev.ide.jvm

/**
 * Helpers for reading JVM type descriptors, and the value conventions the interpreter uses throughout.
 *
 * Computational-int types (boolean, byte, char, short, int) are represented as [Int]; long and double as
 * [Long] and [Double]; float as [Float]; a reference as a [VmObject], [VmArray], a real object, or null. On
 * the operand stack each value occupies a single entry, so a category-2 value is not split into two slots. In
 * the locals array a category-2 value still occupies two consecutive indices, matching the slot numbering the
 * compiler assigns.
 */
internal object Descriptors {
    /** The parameter type descriptors of [methodDescriptor], in declaration order. `(IJ)V` yields `["I", "J"]`. */
    fun paramTypes(methodDescriptor: String): List<String> {
        val out = ArrayList<String>()
        var i = 1 // past '('
        while (methodDescriptor[i] != ')') {
            val start = i
            while (methodDescriptor[i] == '[') i++
            if (methodDescriptor[i] == 'L') i = methodDescriptor.indexOf(';', i) + 1 else i++
            out.add(methodDescriptor.substring(start, i))
        }
        return out
    }

    /** The return type descriptor of [methodDescriptor] (`V` for a void method). */
    fun returnType(methodDescriptor: String): String =
        methodDescriptor.substring(methodDescriptor.indexOf(')') + 1)

    /** Whether [typeDescriptor] denotes a long or double, which occupy two stack and local slots in a class file. */
    fun isCategory2(typeDescriptor: String): Boolean = typeDescriptor == "J" || typeDescriptor == "D"

    /** The initial value of a field or array element of [typeDescriptor] before it is assigned. */
    fun defaultValue(typeDescriptor: String): Any? = when (typeDescriptor) {
        "I", "B", "S", "C", "Z" -> 0
        "J" -> 0L
        "F" -> 0f
        "D" -> 0.0
        else -> null // reference or array
    }
}
