package dev.ide.preview.impl

import dev.ide.preview.ResolvedValue
import java.nio.file.Path

/**
 * Resolves a custom view's styled attributes for the bridge's `obtainStyledAttributes`. Given the view's
 * raw layout attributes (local name → value) and the styleable's attr ids (the `R.styleable.X` int[]), it
 * returns the resolved values in the same order — mapping each id back to its attr name ([RIdAssignment]),
 * reading the raw value, and resolving it ([dev.ide.preview.PreviewResources]).
 */
fun interface StyledAttrResolver {
    fun resolve(rawAttrs: Map<String, String>, styleableIds: IntArray): List<ResolvedValue?>
}

/**
 * Platform seam that turns the preview-compiled, BridgeRemapper-instrumented user classes (in [classesDir]
 * plus their dependency jars) into a [CustomViewFactory]. The device impl (ide-android) dexes them and loads
 * through a `DexClassLoader` against the on-device Bridge runtime; the desktop impl loads through a
 * `URLClassLoader` against the JVM shim. Returns null when no runtime can be built (→ the inflater falls back
 * to placeholders, recorded as render problems).
 */
interface CustomViewRuntime {
    fun createFactory(classesDir: Path, deps: List<Path>, styled: StyledAttrResolver): CustomViewFactory?

    companion object {
        val NONE: CustomViewRuntime = object : CustomViewRuntime {
            override fun createFactory(classesDir: Path, deps: List<Path>, styled: StyledAttrResolver): CustomViewFactory? = null
        }
    }
}
