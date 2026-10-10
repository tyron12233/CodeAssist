package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.lang.incremental.DocumentEdit

/**
 * The quick fixes for the minSdk check ([KotlinApiLevelCheck]), the two Android lint offers:
 *  - annotate the enclosing function (or class) with `@RequiresApi(N)`, `@TargetApi(N)` when androidx's
 *    annotation is not on the classpath;
 *  - wrap the statement in `if (Build.VERSION.SDK_INT >= N) { ... }`.
 */
internal object KotlinApiLevelFixes {

    private const val REQUIRES_API = "androidx.annotation.RequiresApi"
    private const val TARGET_API = "android.annotation.TargetApi"
    private const val BUILD = "android.os.Build"

    /** The level a minSdk diagnostic message names (`Call requires API level 26 (...)`), or null. */
    fun levelIn(message: String): Int? =
        Regex("""requires API level (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()

    fun fixes(ktFile: KtFile, offset: Int, level: Int, hasRequiresApi: Boolean): List<KotlinImportFix> {
        val leaf = ktFile.findElementAt(offset.coerceIn(0, (ktFile.textLength - 1).coerceAtLeast(0))) ?: return emptyList()
        val text = ktFile.text
        val imported = ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toHashSet()
        val out = ArrayList<KotlinImportFix>()

        annotationTarget(leaf)?.let { decl ->
            val annotationFqn = if (hasRequiresApi) REQUIRES_API else TARGET_API
            val simple = annotationFqn.substringAfterLast('.')
            val at = decl.modifierList?.textRange?.startOffset ?: startOfKeyword(decl) ?: decl.textRange.startOffset
            val edits = ArrayList<DocumentEdit>()
            edits += DocumentEdit(at, 0, "@$simple($level)\n${indentAt(text, at)}")
            importEdit(ktFile, annotationFqn, imported)?.let(edits::add)
            out += KotlinImportFix("Add @$simple($level) to '${(decl as? KtNamedDeclaration)?.name ?: "declaration"}'", edits)
        }

        statementAt(leaf)?.let { statement ->
            val r = statement.textRange
            val indent = indentAt(text, r.startOffset)
            val body = text.substring(r.startOffset, r.endOffset).replace("\n", "\n    ")
            val edits = ArrayList<DocumentEdit>()
            edits += DocumentEdit(r.startOffset, r.endOffset - r.startOffset,
                "if (Build.VERSION.SDK_INT >= $level) {\n$indent    $body\n$indent}")
            importEdit(ktFile, BUILD, imported)?.let(edits::add)
            out += KotlinImportFix("Surround with if (Build.VERSION.SDK_INT >= $level)", edits)
        }
        return out
    }

    /** The innermost function, or failing that the innermost class, enclosing [leaf]. */
    private fun annotationTarget(leaf: KtElement): KtDeclaration? {
        var n: KtElement? = leaf
        var cls: KtClassOrObject? = null
        while (n != null) {
            if (n is KtNamedFunction) return n
            if (cls == null && n is KtClassOrObject) cls = n
            n = n.parent
        }
        return cls
    }

    /** The statement of a block that holds [leaf]; null for a local declaration, whose scope a wrap would cut. */
    private fun statementAt(leaf: KtElement): KtExpression? {
        var n: KtElement? = leaf
        while (n != null) {
            val parent = n.parent
            if (parent is KtBlockExpression && n is KtExpression) return n.takeUnless { it is KtDeclaration }
            if (n is KtDeclaration) return null
            n = parent
        }
        return null
    }

    private fun startOfKeyword(decl: KtDeclaration): Int? = when (decl) {
        is KtNamedFunction -> decl.funKeyword?.textRange?.startOffset
        else -> null
    }

    private fun importEdit(ktFile: KtFile, fqn: String, imported: Set<String>): DocumentEdit? {
        if (fqn in imported) return null
        val plan = KotlinImportEdits.planImport(ktFile, fqn) ?: return null
        return DocumentEdit(plan.offset, 0, plan.text)
    }

    /** The whitespace that starts the line holding [offset]. */
    internal fun indentAt(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0 || offset == 0) 0 else it + 1 }
        var end = lineStart
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(lineStart, end)
    }
}
