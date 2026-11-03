package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.core.services.MissingLibraries
import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.IndexService
import dev.ide.index.exactAll
import dev.ide.model.Module
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile

/**
 * "Add library Gson" on an unresolved import, an unknown class name, or an XML tag naming a library view, in
 * Java, Kotlin and XML alike; "Add all N missing libraries" when the file is missing more than one; "Resolve
 * dependencies" when the library is declared but did not download.
 *
 * Adding a library is not a text edit, so the fix's edit is only the import (for a class typed by name) and
 * the library is added in the background as the fix is applied. That is safe because the host computes a
 * fix's edits only when the user picks it (`AnalysisService.computeActionEdits`); listing the menu never does.
 *
 * Host-level because only the host can add a dependency. [missing] and [diagnosticsOf] resolve the active
 * project lazily.
 */
class MissingLibraryFixProvider internal constructor(
    private val missing: () -> MissingLibraries?,
    private val diagnosticsOf: (VirtualFile) -> List<Diagnostic>,
) : QuickFixProvider {
    override val forCodes: Set<String> = MissingLibraries.CODES

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val libraries = missing() ?: return emptyList()
        val need = MissingLibraryScan.need(libraries, diagnostic, target) ?: return emptyList()
        val module = target.module
        val testSource = MissingLibraryScan.isTestSource(target.file.path)

        if (libraries.declares(module, need.library)) {
            if (!libraries.declaredButUnresolved(module, need.library)) return emptyList()
            return listOf(fix("Resolve dependencies (${need.library.label} did not download)") {
                libraries.resolveInBackground()
                WorkspaceEdit.EMPTY
            })
        }

        val out = ArrayList<QuickFix>(2)
        val title = if (need.importFqn != null) "Add library ${need.library.label} and import ${need.importFqn.substringAfterLast('.')}"
        else "Add library ${need.library.label} (${need.library.coordinate})"
        out += fix(title) { ctx ->
            libraries.addInBackground(module.name, listOf(need.library), testSource)
            importEdit(ctx.target, listOfNotNull(need.importFqn))
        }

        val all = MissingLibraryScan.needs(libraries, diagnosticsOf(target.file), target)
        val others = all.map { it.library }.distinctBy { it.coordinate }
        if (others.size >= 2) {
            out += fix("Add all ${others.size} missing libraries") { ctx ->
                libraries.addInBackground(module.name, others, testSource)
                // The imports of every class typed by name, the one under the caret included.
                importEdit(ctx.target, all.mapNotNull { it.importFqn })
            }
        }
        return out
    }

    private fun fix(title: String, edits: suspend (FixContext) -> WorkspaceEdit) = object : QuickFix {
        override val title = title
        override val kind = CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = edits(ctx)
    }

    /** The imports of [fqns] the file does not have yet, as one edit. */
    private fun importEdit(target: AnalysisTarget, fqns: List<String>): WorkspaceEdit {
        if (fqns.isEmpty()) return WorkspaceEdit.EMPTY
        val text = target.parsed.text().toString()
        val edit = MissingLibraryScan.importInsertion(text, fqns, target.file.path.endsWith(".java")) ?: return WorkspaceEdit.EMPTY
        return WorkspaceEdit.of(target.file, edit)
    }
}

/** The pure half of [MissingLibraryFixProvider], shared with the editor notice. */
internal object MissingLibraryScan {

    /** What [diagnostic] in [target] is missing, or null when it is not a missing library (or none is known). */
    fun need(libraries: MissingLibraries, diagnostic: Diagnostic, target: AnalysisTarget): MissingLibraries.Need? =
        need(libraries, diagnostic, target.parsed.text(), target.module, target.index)

    /** [need] over a file's [text], its [module] and the [index] that knows the classpath's classes. */
    fun need(
        libraries: MissingLibraries, diagnostic: Diagnostic, text: CharSequence, module: Module, index: IndexService?,
    ): MissingLibraries.Need? {
        val wanted = libraries.wanted(diagnostic.code, diagnostic.message, text, diagnostic.range.start, diagnostic.range.end)
            ?: return null
        // A class typed by name that the classpath does have is the plain "Import …" fix's job, not a library's.
        if (wanted is MissingLibraries.Wanted.SimpleName && classExists(index, wanted.name)) return null
        val android = isAndroid(module)
        val library = libraries.libraryFor(wanted, android) ?: return null
        return MissingLibraries.Need(library, wanted, libraries.importFor(wanted, android))
    }

    /** Every distinct missing library among [diagnostics], not yet declared by [target]'s module. */
    fun needs(libraries: MissingLibraries, diagnostics: List<Diagnostic>, target: AnalysisTarget): List<MissingLibraries.Need> =
        needs(libraries, diagnostics, target.parsed.text(), target.module, target.index)

    fun needs(
        libraries: MissingLibraries, diagnostics: List<Diagnostic>, text: CharSequence, module: Module, index: IndexService?,
    ): List<MissingLibraries.Need> =
        diagnostics.filter { it.code in MissingLibraries.CODES }
            .mapNotNull { runCatching { need(libraries, it, text, module, index) }.getOrNull() }
            .filter { !libraries.declares(module, it.library) }
            .distinctBy { it.library.coordinate to it.importFqn }

    // No index, or one still building, knows too little to say a class is absent: say it exists.
    private fun classExists(index: IndexService?, simpleName: String): Boolean = index == null || !index.status.ready || runCatching {
        index.exactAll<ClassNameValue>(ClassNameIndex.ALL, simpleName).any()
    }.getOrDefault(true)

    private fun isAndroid(module: Module): Boolean =
        module.facets.get(dev.ide.android.support.AndroidFacet.KEY) != null || module.type.id.startsWith("android")

    fun isTestSource(path: String): Boolean = "/src/test/" in path || "/src/androidTest/" in path

    private val IMPORT_LINE = Regex("""(?m)^[ \t]*import[ \t]+(?:static[ \t]+)?([\w.$`*]+)[^\n]*$""")
    private val PACKAGE_LINE = Regex("""(?m)^[ \t]*package[ \t]+[\w.`]+[ \t]*;?[ \t]*$""")

    /**
     * One edit adding `import` lines for [fqns] to [text], as a sorted block: before the first existing import
     * that sorts after the first of them, else after the last import, else after the package line (with a
     * blank line), else at the top. Null when the file already imports them all.
     */
    fun importInsertion(text: String, fqns: List<String>, java: Boolean): DocumentEdit? {
        val imports = IMPORT_LINE.findAll(text).toList()
        val present = imports.mapTo(HashSet()) { it.groupValues[1].replace("`", "").removeSuffix(";") }
        val add = fqns.distinct().filter { it !in present }.sorted()
        if (add.isEmpty()) return null
        val block = add.joinToString("\n") { "import $it${if (java) ";" else ""}" }
        val first = add.first()
        val after = imports.firstOrNull { it.groupValues[1].replace("`", "") > first && !it.value.contains(" static ") }
        if (after != null) return DocumentEdit(after.range.first, 0, "$block\n")
        imports.lastOrNull()?.let { return DocumentEdit(it.range.last + 1, 0, "\n$block") }
        PACKAGE_LINE.find(text)?.let { return DocumentEdit(it.range.last + 1, 0, "\n\n$block") }
        return DocumentEdit(0, 0, "$block\n\n")
    }
}
