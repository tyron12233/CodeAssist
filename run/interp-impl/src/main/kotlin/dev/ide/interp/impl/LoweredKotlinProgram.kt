package dev.ide.interp.impl

import dev.ide.interp.api.LoweredProgram
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction

/**
 * The concrete [LoweredProgram]: a lowered Kotlin program plus the entry the caller asked for.
 *
 * This is the type that keeps `ResolvedTree` out of the published SPI. `:ide-core` produces one (it owns the
 * analyzers lowering needs), the sessions here consume one, and a plugin only ever sees [LoweredProgram],
 * so the resolver-to-interpreter contract stays free to change without breaking a compiled plugin.
 *
 * [entryFunction] and [entryType] are alternatives: an entry names either a top-level callable or a source
 * type, and exactly one is set.
 */
class LoweredKotlinProgram(
    /** `"name/arity"` → lowered function, spanning the entry file and everything reachable from it. */
    val functions: Map<String, ResolvedFunction>,
    /** Every source type the program can construct. */
    val classes: List<ResolvedClass>,
    /** The lowered entry callable, when the request named a function. */
    val entryFunction: ResolvedFunction? = null,
    /** The lowered entry type, when the request named a class. */
    val entryType: ResolvedClass? = null,
    override val problems: List<String> = emptyList(),
) : LoweredProgram {

    init {
        require((entryFunction == null) != (entryType == null)) {
            "a lowered program has exactly one entry: a function or a type"
        }
    }

    override val entry: String =
        entryFunction?.let { "${it.name}/${it.params.size}" } ?: entryType!!.fqn

    override val types: List<String> get() = classes.map { it.fqn }

    /** The lowered function for [key] (`name`, or `name/arity` to pick an overload), or null. */
    fun function(key: String): ResolvedFunction? {
        functions[key]?.let { return it }
        if ('/' in key) return null
        // A bare name: prefer the entry when it matches (the common case, and the arity the caller lowered
        // for), then any arity of that name.
        entryFunction?.takeIf { it.name == key }?.let { return it }
        return functions.entries.firstOrNull { it.key.substringBeforeLast('/') == key }?.value
    }

    /** The lowered source type for [nameOrFqn] (exact FQN first, then simple name), or null. */
    fun type(nameOrFqn: String): ResolvedClass? =
        classes.firstOrNull { it.fqn == nameOrFqn }
            ?: classes.firstOrNull { it.simpleName == nameOrFqn.substringAfterLast('.') }
}
