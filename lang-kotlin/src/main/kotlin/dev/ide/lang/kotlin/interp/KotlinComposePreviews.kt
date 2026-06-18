package dev.ide.lang.kotlin.interp

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** A `@Preview @Composable` function found in a file — the editor's preview targets. */
data class PreviewInfo(
    val functionName: String,
    /** Source offset of the function declaration (for placing a gutter affordance / navigation). */
    val offset: Int,
    /** Value-parameter count; a previewable composable is normally parameterless (or all-defaulted). */
    val arity: Int,
)

/**
 * Finds the `@Preview`-annotated `@Composable` functions in a file — the entries the editor offers to render
 * through the interpreter. Detection is by annotation simple name (no need to resolve the androidx types),
 * matching how `@Composable`/`inline` are detected elsewhere.
 */
object KotlinComposePreviews {

    fun find(ktFile: KtFile): List<PreviewInfo> =
        ktFile.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.hasAnnotation("Preview") && it.hasAnnotation("Composable") }
            .map { PreviewInfo(it.name ?: "", it.textRange.startOffset, it.valueParameters.size) }

    private fun KtNamedFunction.hasAnnotation(simpleName: String): Boolean =
        annotationEntries.any { it.shortName?.asString() == simpleName }
}
