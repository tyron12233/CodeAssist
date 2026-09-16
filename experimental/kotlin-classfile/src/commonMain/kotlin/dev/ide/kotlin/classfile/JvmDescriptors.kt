package dev.ide.kotlin.classfile

/**
 * Kotlin class names to JVM descriptors, for the signatures the compiler declines to write down.
 *
 * A JVM signature is only recorded in the metadata when it is NOT derivable from the Kotlin one. When it is
 * derivable the compiler omits it and expects the reader to recompute it, so a decoder without this table
 * reports "no signature" for most of the members on the classpath.
 *
 * Ported from the compiler's `ClassMapperLite`, and the warning attached to it there applies here: this is
 * not a general type mapper and must not be improved into one. Its content is a compatibility contract. If
 * this table maps something the compiler's does not, the descriptor produced disagrees with the one actually
 * in the bytecode, and a lookup keyed on it finds nothing.
 */
object JvmDescriptors {

    private val map: Map<String, String> = buildMap {
        val primitives = listOf(
            "Boolean" to "Z",
            "Char" to "C",
            "Byte" to "B",
            "Short" to "S",
            "Int" to "I",
            "Float" to "F",
            "Long" to "J",
            "Double" to "D",
        )
        for ((name, descriptor) in primitives) {
            put("kotlin/$name", descriptor)
            put("kotlin/${name}Array", "[$descriptor")
        }

        put("kotlin/Unit", "V")

        fun add(kotlinSimpleName: String, javaInternalName: String) {
            put("kotlin/$kotlinSimpleName", "L$javaInternalName;")
        }

        add("Any", "java/lang/Object")
        add("Nothing", "java/lang/Void")
        add("Annotation", "java/lang/annotation/Annotation")

        for (name in listOf("String", "CharSequence", "Throwable", "Cloneable", "Number", "Comparable", "Enum")) {
            add(name, "java/lang/$name")
        }

        for (name in listOf("Iterator", "Collection", "List", "Set", "Map", "ListIterator")) {
            add("collections/$name", "java/util/$name")
            add("collections/Mutable$name", "java/util/$name")
        }

        add("collections/Iterable", "java/lang/Iterable")
        add("collections/MutableIterable", "java/lang/Iterable")
        add("collections/Map.Entry", "java/util/Map\$Entry")
        add("collections/MutableMap.MutableEntry", "java/util/Map\$Entry")

        for (arity in 0..22) {
            add("Function$arity", "kotlin/jvm/functions/Function$arity")
            // Every arity of KFunction erases to the same class; that is not a typo.
            add("reflect/KFunction$arity", "kotlin/reflect/KFunction")
        }

        // Boolean is deliberately absent: the compiler omits it so that an older reader, which does not know
        // about companion mapping at all, cannot disagree with a newer writer about `Boolean.Companion`.
        for (name in listOf("Char", "Byte", "Short", "Int", "Float", "Long", "Double", "String", "Enum")) {
            add("$name.Companion", "kotlin/jvm/internal/${name}CompanionObject")
        }
    }

    /**
     * The descriptor for [classId], which is a class name in the metadata's own spelling: slashes between
     * package parts and DOTS between nested names, as in `kotlin/collections/Map.Entry`.
     *
     * Anything not in the table maps structurally, nesting dots becoming `$`.
     */
    fun of(classId: String): String = map[classId] ?: "L${classId.replace('.', '$')};"
}
