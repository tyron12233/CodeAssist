package dev.ide.interp.compose

import dev.ide.interp.KotlinJvmNames
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.JvmNameMatcher
import dev.ide.jvm.VmMethodView
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * The authoritative Kotlin-name → JVM-name resolver for the bytecode-VM library path — the counterpart to
 * :interp-core's [KotlinJvmNames] for reflectively-dispatched code, sharing its decision core so BOTH paths
 * resolve mangled names the one way.
 *
 * It reads each interpreted method's DECLARING class `@kotlin.Metadata` (via [source]) to learn the actual JVM
 * name the compiler emitted — the inline value-class `name-<hash>` and `internal` `name$module` manglings —
 * rather than guessing the name shape. Decode is cached per class; a class with no metadata (or a member it
 * doesn't describe: a bridge/synthetic) falls back to the shared shape heuristic inside [KotlinJvmNames.matches],
 * so nothing metadata omits is wrongly rejected.
 *
 * Looking up by the method's DECLARING class (not the call's receiver type) means an inherited method or a
 * multi-file-facade part method is matched against its own class's metadata, so the facade/part split and
 * cross-class overrides resolve without special-casing. Implements [JvmNameMatcher] so it can also be injected
 * into [dev.ide.jvm.ReifiedInlineExecutor].
 */
internal class VmKotlinNames(private val source: ClassBytesSource) : JvmNameMatcher {

    private val none = emptyMap<String, Set<String>>()
    private val cache = ConcurrentHashMap<String, Map<String, Set<String>>>()

    /** Whether [method] is the compiler's emission of the Kotlin declaration looked up as [kotlinName] (a
     *  function's Kotlin name, or a property's `getX`/`setX` accessor). */
    override fun matches(method: VmMethodView, kotlinName: String): Boolean =
        KotlinJvmNames.matches(indexFor(method.ownerInternalName), method.name, kotlinName)

    /** Whether [method] is the `name$default` synthetic of Kotlin [kotlinName] — the authoritative base-name
     *  match after stripping `$default` (a value-class default is `name-<hash>$default`). */
    fun isDefaultSynthetic(method: VmMethodView, kotlinName: String): Boolean {
        val n = method.name
        if (!n.endsWith("\$default")) return false
        return KotlinJvmNames.matches(indexFor(method.ownerInternalName), n.removeSuffix("\$default"), kotlinName)
    }

    private fun indexFor(internalName: String): Map<String, Set<String>> = cache.getOrPut(internalName) {
        source.bytesFor(internalName)?.let { KotlinMetadata.jvmNameIndex(it) } ?: none
    }
}
