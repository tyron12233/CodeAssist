package dev.codeassist.csharp.editor.quickfixes


import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsSymbols
import dev.codeassist.csharp.editor.CsUsingCleanup
import dev.codeassist.csharp.editor.diagnostics.CsDiagnostics
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.incremental.DocumentEdit
import java.nio.file.Paths

/**
 * Adds using directives for unresolved types, names, and extension methods.
 * Also provides fixes for unnecessary using directives.
 *
 * Fixes are attached to compiler diagnostics by their Roslyn diagnostic codes.
 * The symbol table is read synchronously from [CsSymbols], so fixes are offered
 * only when a cached table is available.
 */
internal class CsUsingFixProvider(
    private val symbols: CsSymbols,
    private val unused: CsUsingCleanup.Unused,
) : QuickFixProvider {

    override val forCodes = CsUsingFix.CODES + CsDiagnostics.UNUSED_USING_CODE

    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        if (diagnostic.code == CsDiagnostics.UNUSED_USING_CODE) {
            return removeFixes(diagnostic, target)
        }

        val extension = diagnostic.code == "CS1061"
        val name = if (extension) {
            CsUsingFix.extensionName(diagnostic.message)
        } else {
            CsUsingFix.missingName(diagnostic.message)
        } ?: return emptyList()

        val moduleDir = runCatching {
            Paths.get(target.module.dir.path)
        }.getOrNull() ?: return emptyList()

        val table = symbols.cachedTable(moduleDir) ?: return emptyList()
        val source = target.parsed.text().toString()

        val namespaces = if (extension) {
            CsUsingFix.extensionCandidates(
                name,
                table,
                source,
                CsUsingFix.extensionReceiver(diagnostic.message),
            )
        } else {
            CsUsingFix.candidates(name, table, source)
        }

        if (namespaces.isNotEmpty() || extension) {
            return namespaces.map { AddUsing(it) }
        }

        return CsUsingFix.staticCandidates(name, table, source).map { AddUsingStatic(it) }
    }

    private fun removeFixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val source = target.parsed.text().toString()
        val line = CsUsingCleanup.directiveAt(source, diagnostic.range.start) ?: return emptyList()

        return listOf(
            RemoveUsing(line),
            OrganizeUsings(target.file.path, unused),
        )
    }

    private class RemoveUsing(private val line: String) : QuickFix {
        override val title = "Remove unnecessary using"
        override val kind = CodeActionKind.QUICK_FIX

        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
            val source = ctx.target.parsed.text().toString()
            return edits(ctx, CsUsingCleanup.remove(source, line))
        }
    }

    private class OrganizeUsings(
        private val path: String,
        private val unused: CsUsingCleanup.Unused,
    ) : QuickFix {
        override val title = "Remove unnecessary usings and sort"
        override val kind = CodeActionKind.QUICK_FIX

        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
            val source = ctx.target.parsed.text().toString()
            return edits(ctx, CsUsingCleanup.organize(source, unused.of(path)))
        }
    }

    private class AddUsingStatic(private val type: String) : QuickFix {
        override val title = "Add using static $type;"
        override val kind = CodeActionKind.QUICK_FIX

        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
            val source = ctx.target.parsed.text().toString()
            if (source.lines().any { it.trim() == CsUsingFix.staticLine(type) }) {
                return WorkspaceEdit.EMPTY
            }

            val insertion = CsUsingFix.staticInsertion(source, type)
            return WorkspaceEdit.of(
                ctx.target.file,
                DocumentEdit(insertion.offset, 0, insertion.text),
            )
        }
    }

    private class AddUsing(private val namespace: String) : QuickFix {
        override val title = "Add using $namespace;"
        override val kind = CodeActionKind.QUICK_FIX

        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
            // Read the live buffer because it may have changed since the fix menu was built.
            val source = ctx.target.parsed.text().toString()
            if (CsUsingFix.imports(source, namespace)) return WorkspaceEdit.EMPTY

            val insertion = CsUsingFix.insertion(source, namespace)
            return WorkspaceEdit.of(
                ctx.target.file,
                DocumentEdit(insertion.offset, 0, insertion.text),
            )
        }
    }
}

/** Creates one atomic workspace edit for the fix's file. */
private fun edits(ctx: FixContext, changes: List<CsUsingCleanup.Edit>): WorkspaceEdit {
    if (changes.isEmpty()) return WorkspaceEdit.EMPTY

    val edits = changes.map { DocumentEdit(it.offset, it.length, it.text) }
    return WorkspaceEdit.of(ctx.target.file, *edits.toTypedArray())
}
