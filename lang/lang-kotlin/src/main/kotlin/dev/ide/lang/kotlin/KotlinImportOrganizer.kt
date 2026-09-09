package dev.ide.lang.kotlin

import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportOrganizerService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * "Optimize Imports" for Kotlin. Re-emits the file's import section through the language-neutral [ImportLayout]:
 * one sorted block (Kotlin has no static-import split), de-duplicated, wildcard-collapsed at the threshold, and
 * with imports whose bound name is never referenced dropped. Star imports (usage can't be told) and the
 * operator-convention names (used implicitly, e.g. `getValue`/`plus`/`component1`) are always kept — this
 * mirrors [KotlinSemanticChecks]'s `kt.unusedImport` heuristic (kept in sync by hand; both are pure PSI scans).
 */
class KotlinImportOrganizer : ImportOrganizerService {

    override suspend fun organizeImports(file: VirtualFile, text: CharSequence): List<DocumentEdit> {
        val src = text.toString()
        if (src.isEmpty()) return emptyList()
        val ktFile = KotlinParserHost.parse(file.name, src)
        val positioned = KotlinImportEdits.existingImports(ktFile)
        if (positioned.isEmpty()) return emptyList()

        val referenced = referencedNames(ktFile)
        val kept = positioned.map { it.entry }.filter { keep(it, referenced) }
        val blocks = ImportLayout.organize(kept, KotlinImportEdits.CONFIG)
        val rendered = ImportLayout.renderBlocks(blocks, KotlinImportEdits::render)

        val regionStart = positioned.first().start
        val regionEnd = positioned.last().endExclusive // past the last import's newline
        val current = src.substring(regionStart, regionEnd)
        val replacement = if (rendered.isEmpty()) "" else "$rendered\n"
        if (replacement == current) return emptyList()
        return listOf(minimalEdit(src, regionStart, regionEnd, replacement))
    }

    /** Keep [entry] unless it is a plain single import whose bound name is neither referenced nor an implicit
     *  operator-convention name. A star import is always kept (its usage can't be determined). */
    private fun keep(entry: ImportEntry, referenced: Set<String>): Boolean {
        if (entry.isWildcard) return true
        val name = entry.alias ?: entry.fqn.substringAfterLast('.')
        return name.isEmpty() || name in OPERATOR_NAMES || name in referenced
    }

    /** Simple names referenced anywhere outside the import/package directives (a `KtSimpleNameExpression` also
     *  covers operator references like `a shl b`, so an imported infix used as an operator counts). */
    private fun referencedNames(file: KtFile): Set<String> {
        val names = HashSet<String>()
        fun rec(p: PsiElement) {
            if (p is KtImportDirective || p is KtPackageDirective) return
            if (p is KtSimpleNameExpression) names += p.getReferencedName()
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(file)
        return names
    }

    /** Replace only the span of `[regionStart, regionEnd)` that differs from [replacement] (common prefix +
     *  suffix trimmed), so an already-optimal import block yields no edit and the caret survives. */
    private fun minimalEdit(src: String, regionStart: Int, regionEnd: Int, replacement: String): DocumentEdit {
        val current = src.substring(regionStart, regionEnd)
        var p = 0
        val max = minOf(current.length, replacement.length)
        while (p < max && current[p] == replacement[p]) p++
        var s = current.length
        var d = replacement.length
        while (s > p && d > p && current[s - 1] == replacement[d - 1]) { s--; d-- }
        return DocumentEdit(regionStart + p, s - p, replacement.substring(p, d))
    }

    private companion object {
        // Convention names used implicitly via operators/delegation/destructuring — never flagged as unused.
        // Mirrors KotlinSemanticChecks.OPERATOR_NAMES.
        val OPERATOR_NAMES = setOf(
            "plus", "minus", "times", "div", "rem", "mod", "plusAssign", "minusAssign", "timesAssign",
            "divAssign", "remAssign", "inc", "dec", "unaryPlus", "unaryMinus", "not", "get", "set", "invoke",
            "contains", "iterator", "next", "hasNext", "compareTo", "equals", "rangeTo", "rangeUntil",
            "provideDelegate", "getValue", "setValue", "component1", "component2", "component3", "component4",
            "component5",
        )
    }
}
