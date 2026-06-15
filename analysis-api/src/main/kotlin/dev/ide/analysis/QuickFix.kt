package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.vfs.VirtualFile

/**
 * Quick-fixes / code actions. A fix produces a [WorkspaceEdit] — text edits across one or more
 * files — applied atomically under the model write lock via the modifiable model. Fixes are computed
 * lazily (only when the fix menu is opened or the diagnostic is hovered) and against a fresh
 * document snapshot at apply time, so an edit computed against a now-stale version is never applied
 * blindly.
 */

/** Distinguishes a fix for a problem from a context action with no diagnostic ([INTENTION]) and a refactor. */
enum class CodeActionKind { QUICK_FIX, INTENTION, REFACTOR }

/**
 * A set of text edits spanning one or more files, applied as a single atomic write action. Reuses the
 * existing edit-delta type [DocumentEdit] (replace `oldLength` chars at `offset` with `newText`) so
 * fixes flow through the same edit machinery as incremental reparsing and the project-model transaction.
 */
data class WorkspaceEdit(val edits: Map<VirtualFile, List<DocumentEdit>>) {
    val isEmpty: Boolean get() = edits.values.all { it.isEmpty() }
    val files: Set<VirtualFile> get() = edits.keys

    companion object {
        val EMPTY = WorkspaceEdit(emptyMap())
        /** A single-file edit — the common case (insert an import, add a `;`, delete a declaration). */
        fun of(file: VirtualFile, vararg edits: DocumentEdit): WorkspaceEdit =
            WorkspaceEdit(mapOf(file to edits.toList()))
    }
}

interface QuickFix {
    val title: String                       // user-facing, e.g. "Import java.util.List"
    val kind: CodeActionKind

    /** Compute the edits against the live state in [ctx]. Suspending: may resolve, query the index, etc. */
    suspend fun computeEdits(ctx: FixContext): WorkspaceEdit
}

/**
 * The context handed to a [QuickFix] when the user invokes it: the live [AnalysisTarget] for the
 * diagnostic's file (DOM, resolver, index, module) plus cooperative cancellation. The edits returned
 * are then applied atomically by [AnalysisService.apply].
 */
interface FixContext {
    val target: AnalysisTarget
    fun checkCanceled()
}

/**
 * Attaches fixes to diagnostics by [Diagnostic.code], including ones the provider did not author,
 * especially the compiler's. The compiler stays fix-agnostic while
 * a provider keyed on `UNRESOLVED_REFERENCE` offers "Import …" actions by querying the class-name index.
 * Registered on [QUICK_FIX_PROVIDER_EP].
 */
interface QuickFixProvider {
    /** Diagnostic codes this provider offers fixes for, e.g. `{ Codes.UNRESOLVED_REFERENCE }`. */
    val forCodes: Set<String>

    fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix>
}

/**
 * Contributes code actions at a caret/selection [TextRange], independent of any diagnostic — the home
 * for intentions and refactorings ("Introduce Local Variable", "Surround with try/catch"). Where a
 * [QuickFixProvider] is keyed by [Diagnostic.code], an action provider is keyed by *position*: the engine
 * calls it with the live [AnalysisTarget] and the editor selection and unions the result with the
 * diagnostic quick-fixes ([AnalysisService.editorActionsAt]). The [QuickFix]es it returns are the same
 * currency a fix uses, so they apply through the same path; they should capture whatever they need from
 * `target`/`range`, since they are computed against the same buffer the action list was built from.
 * Registered on [ACTION_PROVIDER_EP].
 */
interface ActionProvider {
    /** Languages this provider applies to; the engine skips it for files in any other language. */
    val languages: Set<LanguageId>

    /**
     * Actions offered at [range] in [target] — an empty range is the bare caret, a non-empty one a
     * selection. Return empty when nothing applies (the common case for most positions). Must be pure /
     * side-effect-free: it may be called both to *list* actions and again to *compute* the chosen one.
     */
    fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix>
}
