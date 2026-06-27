package dev.ide.lang.kotlin.interp

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** A `@Preview @Composable` function found in a file, expanded to one entry per preview variant. */
data class PreviewInfo(
    val functionName: String,
    /** Source offset of the `@Preview`/MultiPreview annotation that produced this variant (for the gutter). */
    val offset: Int,
    /** Value-parameter count; 0 for a plain preview, or the params fed by [parameter] / defaults. */
    val arity: Int,
    /** The annotation arguments honored when rendering this variant. */
    val config: PreviewConfig = PreviewConfig(),
    /** Stable id distinguishing variants of the same function (selection + render keying). */
    val variantId: String = functionName,
    /** Human label for the preview selector (the `@Preview(name=...)` or a derived one). */
    val label: String = functionName,
    /** The `@PreviewParameter` provider feeding this preview's parameter, if any. */
    val parameter: PreviewParamInfo? = null,
)

/**
 * Finds the previewable `@Composable` functions in a file and expands each into its `@Preview` variants:
 * direct `@Preview(...)` annotations (stacked previews each become a variant), built-in/custom MultiPreview
 * annotations, and `@PreviewParameter`-fed previews. Detection is by annotation simple name (no need to
 * resolve the androidx types), matching how `@Composable`/`inline` are detected elsewhere. A [resolveMulti]
 * hook lets the host expand a cross-file/library MultiPreview annotation it can resolve via the symbol service.
 */
object KotlinComposePreviews {

    fun find(
        ktFile: KtFile,
        resolveMulti: (KtAnnotationEntry) -> List<PreviewConfig>? = { null },
    ): List<PreviewInfo> =
        ktFile.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.hasAnnotation("Composable") }
            .flatMap { fn -> variantsOf(fn, ktFile, resolveMulti) }

    private fun variantsOf(
        fn: KtNamedFunction,
        ktFile: KtFile,
        resolveMulti: (KtAnnotationEntry) -> List<PreviewConfig>?,
    ): List<PreviewInfo> {
        val specs = PreviewAnnotationParser.previewSpecs(fn, ktFile, resolveMulti)
        if (specs.isEmpty()) return emptyList()
        val name = fn.name ?: ""
        val arity = fn.valueParameters.size
        val parameter = PreviewAnnotationParser.parameterFor(fn)
        val multi = specs.size > 1
        return specs.mapIndexed { i, spec ->
            PreviewInfo(
                functionName = name,
                offset = spec.annotationOffset,
                arity = arity,
                config = spec.config,
                variantId = if (multi) "$name#$i" else name,
                label = spec.config.name ?: if (multi) "$name (${i + 1})" else name,
                parameter = parameter,
            )
        }
    }

    private fun KtNamedFunction.hasAnnotation(simpleName: String): Boolean =
        annotationEntries.any { it.shortName?.asString() == simpleName }
}
