package dev.ide.lang.kotlin.interp

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Parses the arguments of a `@Preview` annotation (and expands MultiPreview annotations) into [PreviewConfig]s
 * without a full binding resolve. The standalone PSI host gives us literal expressions; this folds the
 * compile-time-constant subset a preview actually uses (literals, hex/bin numbers, unary minus, named platform
 * constants like `Configuration.UI_MODE_NIGHT_YES` / `Devices.PIXEL_4`, and `a or b` for ui-mode flags). An
 * argument it can't fold is left unset, so the render surface keeps its default rather than guessing.
 */
object PreviewAnnotationParser {

    /** A single preview, plus the source offset of the function-level annotation that produced it (so the
     *  editor can place a gutter affordance on the `@Preview`/MultiPreview line that owns it). */
    data class PreviewSpec(val config: PreviewConfig, val annotationOffset: Int)

    /**
     * Every preview spec [fn] declares: one per direct `@Preview`, plus the constituents of any MultiPreview
     * annotation on it (a built-in androidx one, a same-file annotation class, or whatever [resolveMulti]
     * supplies for a cross-file/library one). Each spec is stamped with its function-level annotation's offset.
     * Empty when [fn] is not a preview target.
     */
    fun previewSpecs(
        fn: KtNamedFunction,
        sameFile: KtFile,
        resolveMulti: (KtAnnotationEntry) -> List<PreviewConfig>? = { null },
    ): List<PreviewSpec> =
        fn.annotationEntries.flatMap { entry ->
            val offset = entry.textRange.startOffset
            specsForAnnotation(entry, sameFile, resolveMulti, HashSet()).map { PreviewSpec(it, offset) }
        }

    /** The configs the [entries] (e.g. the annotations ON a MultiPreview annotation class) expand to. Used by
     *  the host to expand a cross-file/library MultiPreview annotation it located via the symbol service. */
    fun configsForAnnotations(
        entries: List<KtAnnotationEntry>,
        sameFile: KtFile,
        resolveMulti: (KtAnnotationEntry) -> List<PreviewConfig>? = { null },
    ): List<PreviewConfig> = entries.flatMap { specsForAnnotation(it, sameFile, resolveMulti, HashSet()) }

    private fun specsForAnnotation(
        entry: KtAnnotationEntry,
        sameFile: KtFile,
        resolveMulti: (KtAnnotationEntry) -> List<PreviewConfig>?,
        visited: MutableSet<String>,
    ): List<PreviewConfig> {
        val short = entry.shortName?.asString() ?: return emptyList()
        if (short == "Preview") return listOf(parsePreviewArgs(entry))
        PreviewConstants.builtinMultiPreviews[short]?.let { return it }
        if (!visited.add(short)) return emptyList() // a custom annotation can't expand to itself
        // A same-file annotation class used as a MultiPreview: collect the @Preview/MultiPreview specs it carries.
        val annClass = sameFile.declarations.filterIsInstance<KtClass>()
            .firstOrNull { it.isAnnotation() && it.name == short }
        if (annClass != null) {
            val nested = annClass.annotationEntries.flatMap { specsForAnnotation(it, sameFile, resolveMulti, visited) }
            if (nested.isNotEmpty()) return nested
        }
        // A cross-file/library MultiPreview: the host resolves the annotation class through the symbol service.
        return resolveMulti(entry) ?: emptyList()
    }

    /** The single [PreviewConfig] a direct `@Preview(...)` annotation describes. */
    fun parsePreviewArgs(entry: KtAnnotationEntry): PreviewConfig {
        val byName = LinkedHashMap<String, KtExpression?>()
        var positional = 0
        for (arg in entry.valueArguments) {
            val name = arg.getArgumentName()?.asName?.identifier
            val expr = arg.getArgumentExpression()
            if (name != null) byName[name] = expr
            else {
                PreviewConstants.previewArgOrder.getOrNull(positional)?.let { byName[it] = expr }
                positional++
            }
        }
        fun e(n: String) = byName[n]
        return PreviewConfig(
            name = evalString(e("name"))?.ifBlank { null },
            group = evalString(e("group"))?.ifBlank { null },
            apiLevel = evalInt(e("apiLevel"))?.takeIf { it > 0 },
            widthDp = evalInt(e("widthDp"))?.takeIf { it > 0 },
            heightDp = evalInt(e("heightDp"))?.takeIf { it > 0 },
            locale = evalString(e("locale"))?.ifBlank { null },
            fontScale = evalFloat(e("fontScale"))?.takeIf { it > 0f },
            showSystemUi = evalBoolean(e("showSystemUi")) ?: false,
            showBackground = evalBoolean(e("showBackground")) ?: false,
            backgroundColor = evalLong(e("backgroundColor"))?.takeIf { it != 0L },
            uiMode = evalUiMode(e("uiMode"))?.takeIf { it != 0 },
            device = evalDevice(e("device"))?.ifBlank { null },
        )
    }

    /** The `@PreviewParameter` provider on one of [fn]'s value parameters (the first such param), if any. */
    fun parameterFor(fn: KtNamedFunction): PreviewParamInfo? {
        for (p in fn.valueParameters) {
            val ann = p.annotationEntries.firstOrNull { it.shortName?.asString() == "PreviewParameter" } ?: continue
            val byName = LinkedHashMap<String, KtExpression?>()
            var positional = 0
            val order = listOf("provider", "limit")
            for (arg in ann.valueArguments) {
                val name = arg.getArgumentName()?.asName?.identifier
                val expr = arg.getArgumentExpression()
                if (name != null) byName[name] = expr
                else { order.getOrNull(positional)?.let { byName[it] = expr }; positional++ }
            }
            val providerName = (byName["provider"] as? KtClassLiteralExpression)
                ?.receiverExpression?.text?.substringAfterLast('.') ?: continue
            val limit = evalInt(byName["limit"])?.takeIf { it > 0 } ?: Int.MAX_VALUE
            return PreviewParamInfo(providerName, limit)
        }
        return null
    }

    // --- constant folding ------------------------------------------------------------------------------

    private fun evalString(expr: KtExpression?): String? {
        val e = expr as? KtStringTemplateExpression ?: return null
        val sb = StringBuilder()
        for (part in e.entries) {
            when (part) {
                is KtLiteralStringTemplateEntry -> sb.append(part.text)
                is KtEscapeStringTemplateEntry -> sb.append(part.unescapedValue)
                else -> return null // an interpolated ${...} isn't a compile-time constant
            }
        }
        return sb.toString()
    }

    private fun evalBoolean(expr: KtExpression?): Boolean? = when ((expr as? KtConstantExpression)?.text) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun evalInt(expr: KtExpression?): Int? = numberText(expr)?.let { parseLong(it)?.toInt() }

    private fun evalLong(expr: KtExpression?): Long? = numberText(expr)?.let { parseLong(it) }

    private fun evalFloat(expr: KtExpression?): Float? = numberText(expr)?.trimEnd('f', 'F')?.toFloatOrNull()

    private fun evalUiMode(expr: KtExpression?): Int? {
        val e = expr ?: return null
        if (e is KtBinaryExpression && e.operationReference.text == "or") {
            val l = evalUiMode(e.left) ?: return null
            val r = evalUiMode(e.right) ?: return null
            return l or r
        }
        lastNameSegment(e)?.let { name -> PreviewConstants.uiModeConstants[name]?.let { return it } }
        return evalInt(e)
    }

    private fun evalDevice(expr: KtExpression?): String? {
        evalString(expr)?.let { return it }
        return lastNameSegment(expr)?.let { PreviewConstants.deviceConstants[it] }
    }

    /** The trailing identifier of a name/`A.B.NAME` reference (so `Configuration.UI_MODE_NIGHT_YES` → `UI_MODE_NIGHT_YES`). */
    private fun lastNameSegment(expr: KtExpression?): String? = when (expr) {
        is KtNameReferenceExpression -> expr.getReferencedName()
        is KtDotQualifiedExpression ->
            (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: expr.text.substringAfterLast('.')
        else -> null
    }

    /** The numeric literal text of a constant (or unary-minus of one), underscores stripped; null otherwise. */
    private fun numberText(expr: KtExpression?): String? {
        var e = expr ?: return null
        var negate = false
        if (e is KtPrefixExpression && e.operationReference.text == "-") {
            negate = true
            e = e.baseExpression ?: return null
        }
        if (e !is KtConstantExpression) return null
        val t = e.text.replace("_", "")
        return if (negate) "-$t" else t
    }

    private fun parseLong(raw: String): Long? {
        var s = raw.trim()
        val negate = s.startsWith("-").also { if (it) s = s.substring(1) }
        s = s.trimEnd('L', 'l', 'u', 'U')
        val v = when {
            s.startsWith("0x", true) -> s.substring(2).toLongOrNull(16)
            s.startsWith("0b", true) -> s.substring(2).toLongOrNull(2)
            else -> s.toLongOrNull()
        } ?: return null
        return if (negate) -v else v
    }
}
