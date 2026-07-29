package dev.ide.interp

import dev.ide.lang.kotlin.symbols.KotlinMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves whether a JVM method NAME on a class is the compiler's emission of a given Kotlin declaration,
 * the way kotlin-reflect does it: by reading the class's `@kotlin.Metadata` — whose stored JVM signatures
 * ARE the mangled names — rather than guessing the mangling shape.
 *
 * The Kotlin compiler never reverse-matches a mangled name back to a Kotlin name; it emits the mangled name
 * deterministically from a declaration's signature/visibility/module (`name-<hash>` for an inline value-class
 * parameter, `name$module` for `internal`) and, when reading a compiled class, reads that name straight out of
 * `@Metadata`. [matches] follows the second path: for a Kotlin [cls] whose metadata describes [lookupName], it
 * accepts ONLY the exact JVM names the compiler recorded, so a differently-mangled sibling (a wrong `$module`,
 * an unrelated `name-<hash>`) is rejected precisely instead of slipping through a shape check.
 *
 * When the class carries no metadata (a Java class), the member isn't described by it (a synthetic/bridge
 * method, a multi-file facade whose members live in part classes), or [lookupName] isn't a declaration the
 * metadata names, there is no authoritative answer and it falls back to the shape-based [mangledNameMatches]
 * — so nothing metadata simply doesn't cover is ever wrongly rejected.
 */
object KotlinJvmNames {

    private val NONE = emptyMap<String, Set<String>>()
    /** Per-class authoritative index, cached across the interpreter's lifetime (classes are long-lived; a
     *  preview touches at most hundreds). [NONE] memoizes "not Kotlin / nothing to map" so the decode is
     *  attempted once per class. */
    private val cache = ConcurrentHashMap<Class<*>, Map<String, Set<String>>>()

    /**
     * The shared decision, given a prebuilt [index] mapping a lookup name (a function's Kotlin name, or a
     * property's `getX`/`setX` accessor) to the exact JVM names the compiler emitted for it. When [index]
     * names [lookupName], ONLY those emitted names match (authoritative — the value-class `-<hash>` and
     * `internal` `$module` manglings resolve precisely); otherwise there is no authoritative answer and the
     * shape-based [mangledNameMatches] decides. [index] null / not naming [lookupName] both fall back, so a
     * Java class or a member metadata doesn't describe (a bridge/synthetic) still resolves as before.
     *
     * Hosts build [index] however they can reach the metadata: from a loaded [Class] ([matches] below), or
     * from raw class bytes via [dev.ide.lang.kotlin.symbols.KotlinMetadata.jvmNameIndex] (the bytecode-VM
     * library executor). One decision core, so every path resolves mangled names the same way.
     */
    fun matches(index: Map<String, Set<String>>?, jvmName: String, lookupName: String): Boolean {
        index?.get(lookupName)?.let { return jvmName in it }
        return mangledNameMatches(jvmName, lookupName)
    }

    /** Whether JVM method [jvmName] declared on (or inherited into) [cls] is the compiler's emission of the
     *  Kotlin declaration a caller looks up as [lookupName]. Reads [cls]'s (hierarchy-merged) `@Metadata`. */
    fun matches(cls: Class<*>, jvmName: String, lookupName: String): Boolean =
        matches(indexFor(cls), jvmName, lookupName)

    private fun indexFor(cls: Class<*>): Map<String, Set<String>> = cache.getOrPut(cls) { build(cls) }

    /**
     * Merge the `@Metadata` name maps of [cls] and its whole supertype closure. `@kotlin.Metadata` is not
     * `@Inherited`, so [Class.getAnnotation] reports only a class's OWN metadata — but [Class.getMethods]
     * surfaces inherited methods, and an overload of a name can be declared on a supertype (a value-class
     * overload `f(Cents)` on the class, `f(Dollars)` on its parent, each with its own mangled name). Walking
     * the hierarchy keeps the accepted set complete, so the arity/type disambiguation downstream still sees
     * every real candidate.
     */
    private fun build(cls: Class<*>): Map<String, Set<String>> {
        val merged = HashMap<String, MutableSet<String>>()
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(cls) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            runCatching { c.getAnnotation(Metadata::class.java) }.getOrNull()
                ?.let { md -> runCatching { KotlinMetadata.jvmNameIndex(md) }.getOrNull() }
                ?.forEach { (k, v) -> merged.getOrPut(k) { HashSet() }.addAll(v) }
            c.superclass?.let(queue::add)
            c.interfaces.forEach(queue::add)
        }
        return if (merged.isEmpty()) NONE else merged.mapValues { (_, v) -> v.toSet() }
    }
}
