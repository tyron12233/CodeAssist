package dev.ide.lang.kotlin.symbols

/**
 * The small, hardcoded slice of Kotlin's built-in knowledge the backend needs. Kotlin's core types
 * (`String`, `Int`, `List`, etc.) are mapped types: they have no `.class` in the stdlib jar; their own
 * members live on the Java type they map to (`java.lang.String`, `java.util.List`), read via the shared
 * `java.members` index, while their Kotlin extensions (`trim`, `map`, etc.) live in `@Metadata` facades
 * that are decoded. Bridging the two needs (a) the Kotlin→Java type map and (b) the well-known stdlib
 * supertype chains (so `list.map`, whose receiver is `Iterable`, is offered on a `List`). This is the
 * standard `JavaToKotlinClassMap` subset, hand-listed.
 */
object Builtins {

    /** Kotlin classifier FQN -> the JVM type whose bytecode carries its own members. */
    val KOTLIN_TO_JAVA: Map<String, String> = mapOf(
        "kotlin.Any" to "java.lang.Object",
        "kotlin.String" to "java.lang.String",
        "kotlin.CharSequence" to "java.lang.CharSequence",
        "kotlin.Comparable" to "java.lang.Comparable",
        "kotlin.Number" to "java.lang.Number",
        "kotlin.Throwable" to "java.lang.Throwable",
        "kotlin.Cloneable" to "java.lang.Cloneable",
        "kotlin.Enum" to "java.lang.Enum",
        "kotlin.Int" to "java.lang.Integer",
        "kotlin.Long" to "java.lang.Long",
        "kotlin.Short" to "java.lang.Short",
        "kotlin.Byte" to "java.lang.Byte",
        "kotlin.Double" to "java.lang.Double",
        "kotlin.Float" to "java.lang.Float",
        "kotlin.Boolean" to "java.lang.Boolean",
        "kotlin.Char" to "java.lang.Character",
        "kotlin.collections.Iterable" to "java.lang.Iterable",
        "kotlin.collections.Collection" to "java.util.Collection",
        "kotlin.collections.List" to "java.util.List",
        "kotlin.collections.MutableList" to "java.util.List",
        "kotlin.collections.Set" to "java.util.Set",
        "kotlin.collections.MutableSet" to "java.util.Set",
        "kotlin.collections.Map" to "java.util.Map",
        "kotlin.collections.MutableMap" to "java.util.Map",
        "kotlin.collections.Map.Entry" to "java.util.Map\$Entry",
        "kotlin.collections.MutableCollection" to "java.util.Collection",
        "kotlin.collections.MutableIterable" to "java.lang.Iterable",
    )

    /** Well-known stdlib supertype chains (Kotlin names), so extension lookup can walk up a mapped type. */
    val SUPERTYPES: Map<String, List<String>> = mapOf(
        "kotlin.String" to listOf("kotlin.CharSequence", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.collections.List" to listOf("kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.MutableList" to listOf(
            "kotlin.collections.List", "kotlin.collections.MutableCollection",
            "kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.Set" to listOf("kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.MutableSet" to listOf(
            "kotlin.collections.Set", "kotlin.collections.MutableCollection",
            "kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.Collection" to listOf("kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.MutableCollection" to listOf("kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.Map" to listOf("kotlin.Any"),
        "kotlin.collections.MutableMap" to listOf("kotlin.collections.Map", "kotlin.Any"),
        "kotlin.Int" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Long" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Double" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Float" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
    )

    /** Simple stdlib type names that resolve by default import (so `val s: String` / literals type). */
    val DEFAULT_SIMPLE_TYPES: Map<String, String> = buildMap {
        listOf(
            "Any", "Unit", "Nothing", "String", "CharSequence", "Comparable", "Number", "Throwable",
            "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char",
            "Array", "IntArray", "LongArray", "DoubleArray", "BooleanArray", "CharArray",
            "Pair", "Triple", "Function",
        ).forEach { put(it, "kotlin.$it") }
        listOf(
            "Iterable", "Collection", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
            "ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet",
        ).forEach { put(it, "kotlin.collections.$it") }
    }

    fun javaTypeFor(kotlinFqn: String): String? = KOTLIN_TO_JAVA[kotlinFqn]

    fun builtinSupertypes(fqn: String): List<String> = SUPERTYPES[fqn] ?: emptyList()

    /**
     * Whether [fqn] is a mapped Kotlin built-in (List, String, Int, etc.). Its members are approximated
     * from the JVM type it maps to: correct enough for instance access, but the mapped type's statics
     * (`java.util.List.of`, `java.lang.String.valueOf`) are NOT part of the Kotlin type's API, so they must
     * not surface on type access (`List.`). Kotlin's true built-in declarations live in `.kotlin_builtins`
     * binaries that kotlin-metadata-jvm can't read; decoding those is the precise fix.
     */
    fun isMappedBuiltin(fqn: String): Boolean = fqn in KOTLIN_TO_JAVA
}
