package dev.ide.preview.impl

import dev.ide.preview.ResolvedValue
import java.nio.file.Path

/**
 * Thrown when a custom view (or the runtime that hosts it) can't be previewed, carrying a human-readable
 * reason. The inflater catches it and records the message as a [dev.ide.preview.PreviewProblem] so the
 * failure is visible in the preview pane instead of silently degrading to a generic placeholder — without
 * a message the user has no way to tell whether the preview compile failed, dexing failed, a constructor
 * threw, or there's simply no runtime configured.
 */
class CustomViewPreviewException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
