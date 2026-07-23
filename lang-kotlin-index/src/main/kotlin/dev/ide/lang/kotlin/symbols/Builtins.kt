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
        "kotlin.Annotation" to "java.lang.annotation.Annotation",
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
        "kotlin.collections.Map.Entry" to $$"java.util.Map$Entry",
        "kotlin.collections.MutableMap.MutableEntry" to $$"java.util.Map$Entry",
        "kotlin.collections.MutableCollection" to "java.util.Collection",
        "kotlin.collections.MutableIterable" to "java.lang.Iterable",
        "kotlin.collections.Iterator" to "java.util.Iterator",
        "kotlin.collections.MutableIterator" to "java.util.Iterator",
        "kotlin.collections.ListIterator" to "java.util.ListIterator",
        "kotlin.collections.MutableListIterator" to "java.util.ListIterator",
        // The concrete-class typealiases in `kotlin/collections/TypeAliases.kt`. Unlike the read-only/mutable
        // interfaces above (real built-ins with their own `.kotlin_builtins` shape), these are `typealias`es —
        // no `.kotlin_builtins`, no `.class` — so their members (`.add`/`.get`/`.put`, …) live ONLY on the
        // aliased java.util type. Without this, `val l = ArrayList<String>()` resolved to a shapeless FQN and
        // completed nothing. NOTE: intentionally NOT added to JAVA_TO_KOTLIN — a bytecode `java.util.ArrayList`
        // must keep resolving directly to its own shape, not fold back into the shapeless Kotlin alias.
        "kotlin.collections.ArrayList" to "java.util.ArrayList",
        "kotlin.collections.HashMap" to "java.util.HashMap",
        "kotlin.collections.HashSet" to "java.util.HashSet",
        "kotlin.collections.LinkedHashMap" to "java.util.LinkedHashMap",
        "kotlin.collections.LinkedHashSet" to "java.util.LinkedHashSet",
        // `kotlin.Comparator` is a typealias to `java.util.Comparator` (no `.kotlin_builtins`, no `.class`), so
        // its members — the SAM `compare(T, T)` above all — live on the java.util type. Without this,
        // `Comparator<String> { a, b -> … }` resolved to a shapeless FQN, leaving the SAM lambda's params untyped.
        "kotlin.Comparator" to "java.util.Comparator",
        // `kotlin.text.StringBuilder`/`StringBuffer` are typealiases to the java.lang types (`kotlin/text/
        // TypeAliases.kt`) — no `.kotlin_builtins`, no `.class` — so their members (`append`/`insert`/`toString`,
        // which chain fluently, each returning `java.lang.StringBuilder`) live only on the aliased java.lang type.
        // Without this, `StringBuilder().append("x")` resolved to a shapeless FQN and completed / chained nothing.
        "kotlin.text.StringBuilder" to "java.lang.StringBuilder",
        "kotlin.text.StringBuffer" to "java.lang.StringBuffer",
    )

    /**
     * Well-known stdlib supertype chains (Kotlin names), so extension lookup can walk up a mapped type. Each
     * value is the type's FULL transitive supertype closure; the consumer also recurses, so a missing tail is
     * still recovered, but listing the closure keeps the map self-describing and correct on the dumb-mode /
     * standalone path (before the `.kotlin_builtins`-decoded shapes are available). Every chain bottoms out at
     * `kotlin.Any`.
     */
    val SUPERTYPES: Map<String, List<String>> = mapOf(
        "kotlin.CharSequence" to listOf("kotlin.Any"),
        "kotlin.Comparable" to listOf("kotlin.Any"),
        "kotlin.Number" to listOf("kotlin.Any"),
        "kotlin.String" to listOf("kotlin.CharSequence", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.collections.Iterable" to listOf("kotlin.Any"),
        "kotlin.collections.MutableIterable" to listOf("kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.List" to listOf(
            "kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any"
        ),
        "kotlin.collections.MutableList" to listOf(
            "kotlin.collections.List", "kotlin.collections.MutableCollection",
            "kotlin.collections.Collection", "kotlin.collections.MutableIterable",
            "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.Set" to listOf(
            "kotlin.collections.Collection", "kotlin.collections.Iterable", "kotlin.Any"
        ),
        "kotlin.collections.MutableSet" to listOf(
            "kotlin.collections.Set", "kotlin.collections.MutableCollection",
            "kotlin.collections.Collection", "kotlin.collections.MutableIterable",
            "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.Collection" to listOf("kotlin.collections.Iterable", "kotlin.Any"),
        "kotlin.collections.MutableCollection" to listOf(
            "kotlin.collections.Collection", "kotlin.collections.MutableIterable",
            "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.Map" to listOf("kotlin.Any"),
        "kotlin.collections.MutableMap" to listOf("kotlin.collections.Map", "kotlin.Any"),
        "kotlin.collections.Map.Entry" to listOf("kotlin.Any"),
        "kotlin.collections.MutableMap.MutableEntry" to listOf("kotlin.collections.Map.Entry", "kotlin.Any"),
        "kotlin.collections.Iterator" to listOf("kotlin.Any"),
        "kotlin.collections.MutableIterator" to listOf("kotlin.collections.Iterator", "kotlin.Any"),
        "kotlin.collections.ListIterator" to listOf("kotlin.collections.Iterator", "kotlin.Any"),
        "kotlin.collections.MutableListIterator" to listOf(
            "kotlin.collections.ListIterator", "kotlin.collections.MutableIterator",
            "kotlin.collections.Iterator", "kotlin.Any",
        ),
        // The concrete-class typealiases (see KOTLIN_TO_JAVA). Their supertype closure — the mutable-collection
        // chain, since a concrete collection is mutable — so extension lookup (`list.map`, keyed on `Iterable`)
        // walks up even in dumb mode / standalone before the java.util bytecode supertypes are read.
        "kotlin.collections.ArrayList" to listOf(
            "kotlin.collections.MutableList", "kotlin.collections.List",
            "kotlin.collections.MutableCollection", "kotlin.collections.Collection",
            "kotlin.collections.MutableIterable", "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.HashSet" to listOf(
            "kotlin.collections.MutableSet", "kotlin.collections.Set",
            "kotlin.collections.MutableCollection", "kotlin.collections.Collection",
            "kotlin.collections.MutableIterable", "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.LinkedHashSet" to listOf(
            "kotlin.collections.MutableSet", "kotlin.collections.Set",
            "kotlin.collections.MutableCollection", "kotlin.collections.Collection",
            "kotlin.collections.MutableIterable", "kotlin.collections.Iterable", "kotlin.Any",
        ),
        "kotlin.collections.HashMap" to listOf(
            "kotlin.collections.MutableMap", "kotlin.collections.Map", "kotlin.Any",
        ),
        "kotlin.collections.LinkedHashMap" to listOf(
            "kotlin.collections.MutableMap", "kotlin.collections.Map", "kotlin.Any",
        ),
        "kotlin.Comparator" to listOf("kotlin.Any"),
        // The StringBuilder/StringBuffer typealiases (see KOTLIN_TO_JAVA): a CharSequence, so CharSequence
        // extensions (and Any's scope functions) walk up from it in dumb mode / standalone.
        "kotlin.text.StringBuilder" to listOf("kotlin.CharSequence", "kotlin.Any"),
        "kotlin.text.StringBuffer" to listOf("kotlin.CharSequence", "kotlin.Any"),
        "kotlin.Enum" to listOf("kotlin.Comparable", "kotlin.Any"),
        "kotlin.Boolean" to listOf("kotlin.Comparable", "kotlin.Any"),
        "kotlin.Char" to listOf("kotlin.Comparable", "kotlin.Any"),
        "kotlin.Byte" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Short" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Int" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Long" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Float" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),
        "kotlin.Double" to listOf("kotlin.Number", "kotlin.Comparable", "kotlin.Any"),

    )

    /** Simple stdlib type names that resolve by default import (so `val s: String` / literals type). */
    val DEFAULT_SIMPLE_TYPES: Map<String, String> = buildMap {
        listOf(
            "Any", "Unit", "Nothing", "String", "CharSequence", "Comparable", "Number", "Throwable",
            "Enum", "Annotation", "Cloneable",
            "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char",
            "Array", "IntArray", "LongArray", "ShortArray", "ByteArray", "DoubleArray", "FloatArray",
            "BooleanArray", "CharArray",
            "Pair", "Triple", "Function", "Comparator",
        ).forEach { put(it, "kotlin.$it") }
        listOf(
            "Iterable",
            "MutableIterable",
            "Collection",
            "MutableCollection",
            "List",
            "MutableList",
            "Set",
            "MutableSet",
            "Map",
            "MutableMap",
            "Iterator",
            "MutableIterator",
            "ListIterator",
            "MutableListIterator",
            "ArrayList",
            "HashMap",
            "HashSet",
            "LinkedHashMap",
            "LinkedHashSet",
        ).forEach { put(it, "kotlin.collections.$it") }
        // `kotlin.text` is a default star import; its `StringBuilder`/`StringBuffer` typealiases resolve bare.
        put("StringBuilder", "kotlin.text.StringBuilder")
        put("StringBuffer", "kotlin.text.StringBuffer")
    }

    /**
     * The REVERSE map (JVM type → its Kotlin classifier), so a value whose type came from Java bytecode
     * (`CharSequence.toString()` → `java.lang.String`, `List` API → `java.util.List`) is enumerated AS the
     * Kotlin type — getting its real Kotlin members AND its extensions (`String.uppercase`, `Iterable.map`),
     * which are keyed on the Kotlin FQN. Mutable variants are chosen for the collections (a JVM collection is
     * mutable), since they expose the superset of members + the same extensions. This is the
     * `JavaToKotlinClassMap` direction; `boolean`/`int`/… primitives are already mapped at the bytecode reader.
     */
    val JAVA_TO_KOTLIN: Map<String, String> = mapOf(
        "java.lang.Object" to "kotlin.Any",
        "java.lang.String" to "kotlin.String",
        "java.lang.CharSequence" to "kotlin.CharSequence",
        "java.lang.Comparable" to "kotlin.Comparable",
        "java.lang.Number" to "kotlin.Number",
        "java.lang.Throwable" to "kotlin.Throwable",
        "java.lang.Cloneable" to "kotlin.Cloneable",
        "java.lang.Enum" to "kotlin.Enum",
        "java.lang.annotation.Annotation" to "kotlin.Annotation",
        "java.lang.Integer" to "kotlin.Int",
        "java.lang.Long" to "kotlin.Long",
        "java.lang.Short" to "kotlin.Short",
        "java.lang.Byte" to "kotlin.Byte",
        "java.lang.Double" to "kotlin.Double",
        "java.lang.Float" to "kotlin.Float",
        "java.lang.Boolean" to "kotlin.Boolean",
        "java.lang.Character" to "kotlin.Char",
        "java.lang.Iterable" to "kotlin.collections.MutableIterable",
        "java.util.Collection" to "kotlin.collections.MutableCollection",
        "java.util.List" to "kotlin.collections.MutableList",
        "java.util.Set" to "kotlin.collections.MutableSet",
        "java.util.Map" to "kotlin.collections.MutableMap",
        $$"java.util.Map$Entry" to "kotlin.collections.MutableMap.MutableEntry",
        "java.util.Iterator" to "kotlin.collections.MutableIterator",
        "java.util.ListIterator" to "kotlin.collections.MutableListIterator",
    )

    /**
     * JDK methods the Kotlin compiler surfaces onto a mapped collection built-in that are NOT declared in its
     * `.kotlin_builtins` shape — the "additional built-in class members" Kotlin's `JvmBuiltInsCustomizer`
     * grafts from the mapped `java.util.*` type (`MutableList.replaceAll`/`sort`, `Map.getOrDefault`, …). They
     * are callable Kotlin but invisible to a pure `.kotlin_builtins` decode, so member enumeration must pull
     * them from the JVM type ([javaTypeFor]). Keyed by the EXACT Kotlin classifier FQN so a mutating method
     * lands only on the mutable type (a read-only `List` never inherits `replaceAll`); the non-mutating
     * `stream`/`spliterator`/`forEach`/`getOrDefault` sit on the read-only type and reach the mutable one by
     * inheritance. Names only — the consumer reads the actual signatures off the java bytecode shape.
     */
    val ADDITIONAL_JVM_MEMBERS: Map<String, Set<String>> = mapOf(
        "kotlin.collections.Iterable" to setOf("forEach", "spliterator"),
        "kotlin.collections.Collection" to setOf("stream", "parallelStream"),
        "kotlin.collections.MutableCollection" to setOf("removeIf"),
        "kotlin.collections.MutableList" to setOf("replaceAll", "sort"),
        "kotlin.collections.Map" to setOf("getOrDefault", "forEach"),
        "kotlin.collections.MutableMap" to setOf(
            "putIfAbsent", "remove", "replace", "replaceAll", "merge",
            "compute", "computeIfAbsent", "computeIfPresent",
        ),
    )

    /**
     * Declaration-site variance for well-known types whose JVM form ERASES it: `kotlin.Comparator` is a
     * `typealias` to `java.util.Comparator` (bytecode carries no variance), but Kotlin's `Comparator<in T>` is
     * contravariant. Keyed by Kotlin FQN, positional with the type parameters (`"out"`/`"in"`/`""`). Consulted
     * by `classTypeParameterVariance` ahead of the (variance-less) java shape, so `Comparator<Number>` is
     * correctly a subtype of `Comparator<Int>`. Function types (`kotlin.FunctionN` = `<in P…, out R>`) are
     * handled separately (their arity is variable).
     */
    val DECLARATION_VARIANCE: Map<String, List<String>> = mapOf(
        "kotlin.Comparator" to listOf("in"),
    )

    fun javaTypeFor(kotlinFqn: String): String? = KOTLIN_TO_JAVA[kotlinFqn]

    /** The Kotlin classifier a JVM type maps to (`java.lang.String` → `kotlin.String`), or null if unmapped. */
    fun kotlinTypeFor(javaFqn: String): String? = JAVA_TO_KOTLIN[javaFqn]

    fun builtinSupertypes(fqn: String): List<String> = SUPERTYPES[fqn] ?: emptyList()

    /**
     * Whether [fqn] is a mapped Kotlin built-in (List, String, Int, etc.). Its members are approximated
     * from the JVM type it maps to: correct enough for instance access, but the mapped type's statics
     * (`java.util.List.of`, `java.lang.String.valueOf`) are NOT part of the Kotlin type's API, so they must
     * not surface on type access (`List.`). Kotlin's true built-in declarations live in `.kotlin_builtins`
     * binaries that kotlin-metadata-jvm can't read; decoding this is the precise fix.
     */
    fun isMappedBuiltin(fqn: String): Boolean = fqn in KOTLIN_TO_JAVA
}
