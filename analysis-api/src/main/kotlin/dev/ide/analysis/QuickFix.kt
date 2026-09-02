package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
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

    /** Languages this provider applies to; **empty = all languages**. Lets a code-keyed fix stay
     *  language-scoped even when several languages emit a diagnostic sharing the code (e.g. Java's
     *  "Add import" on `UNRESOLVED_REFERENCE` must not attach to a Kotlin/XML diagnostic of the same code). */
    val languages: Set<LanguageId> get() = emptySet()

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
     *
     * Prefer overriding [actions] with an [EditorActionContext] instead: it hands over the caret node and
     * its ancestor chain already walked, which is what most providers do first anyway. This overload stays
     * for providers that only need the range, and is what the context overload delegates to by default.
     */
    fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> = emptyList()

    /**
     * Actions offered for [ctx]: the same contract as the two-argument [actions], plus the resolved caret
     * position (the innermost node, its ancestors, and a flat snapshot). Defaults to the two-argument form,
     * so a provider written before this existed is unaffected.
     *
     * Override exactly one of the two. Both default to nothing, so a provider that overrides neither
     * contributes nothing rather than failing to compile.
     */
    fun actions(ctx: EditorActionContext): List<QuickFix> = actions(ctx.target, ctx.range)
}

/**
 * The position an [ActionProvider] is being asked about: the live [target] and [range] as before, plus the
 * caret's place in the tree, resolved once by the engine instead of by every provider.
 *
 * This is the rich half of the two-tier editor-action model. Everything here is live ([node] holds parent
 * and child pointers, [AnalysisTarget.resolver] resolves symbols and types, [AnalysisTarget.index] answers
 * project-wide questions), so a provider can walk and query freely. It cannot cross a process or DTO
 * boundary: the flat, portable half is [CaretSnapshot], and an action that needs only that belongs on the
 * plugin-api `EDITOR` place instead.
 *
 * Being handed to `actions()`, this is on the listing path, which runs on caret moves. Keep the work here
 * structural (kinds, ranges, ancestors) and leave resolution to the chosen fix's `computeEdits`: the
 * engine builds [target] without bindings for listing, so a resolver call here pays a re-parse.
 */
interface EditorActionContext {
    /** The file's live analysis target: DOM, resolver, index, module. */
    val target: AnalysisTarget

    /** The editor selection. `start == end` is a bare caret. */
    val range: TextRange

    /** The innermost node containing [TextRange.start] of [range], as returned by [ParsedFile.nodeAt]. */
    val node: DomNode

    /** [node]'s ancestors, innermost first, ending at the [ParsedFile] root. Excludes [node] itself. */
    val ancestors: List<DomNode>

    /** The flat, portable view of the same position, as handed to the plugin-api tier. */
    val caret: CaretSnapshot

    fun checkCanceled()

    /** The nearest ancestor (or [node] itself) of kind [kind], or null. */
    fun nearest(kind: NodeKind): DomNode? =
        if (node.kind == kind) node else ancestors.firstOrNull { it.kind == kind }

    /** The nearest enclosing statement: the ancestor that is a direct child of a [NodeKind.BLOCK]. */
    fun enclosingStatement(): DomNode? {
        if (node.parent?.kind == NodeKind.BLOCK) return node
        return ancestors.firstOrNull { it.parent?.kind == NodeKind.BLOCK }
    }
}

/**
 * A flat, serializable snapshot of what the caret is on, built once per listing pass from the syntax-only
 * tree. The portable currency shared by both editor-action tiers: the engine hands it to plugin-api's
 * `CaretContext` across the `IdeBackend` port, and to [EditorActionContext.caret] beside the live tree.
 *
 * [nodeText] is capped at [MAX_NODE_TEXT]; a caret inside a large declaration would otherwise copy the
 * whole thing on every caret move. [nodeTextTruncated] records when that happened, so an action needing
 * exact text reads it off [AnalysisTarget.parsed] rather than trusting a prefix.
 */
data class CaretSnapshot(
    val offset: Int,
    val languageId: LanguageId? = null,
    val nodeKind: String = "",
    val nodeRange: TextRange = TextRange(offset, offset),
    val nodeText: String = "",
    val nodeTextTruncated: Boolean = false,
    val ancestors: List<String> = emptyList(),
) {
    companion object {
        /** The cap on [nodeText]. Matches plugin-api's `CaretContext.MAX_NODE_TEXT`. */
        const val MAX_NODE_TEXT = 4096

        /** Build a snapshot for [offset] in [parsed]. The one place either tier's caret view is derived. */
        fun of(parsed: ParsedFile, offset: Int, language: LanguageId? = null): CaretSnapshot {
            val clamped = offset.coerceIn(0, parsed.range.end)
            val node = parsed.nodeAt(clamped)
            val text = node.text()
            val truncated = text.length > MAX_NODE_TEXT
            val ancestors = ArrayList<String>()
            var p = node.parent
            while (p != null) {
                ancestors.add(p.kind.id)
                p = p.parent
            }
            return CaretSnapshot(
                offset = clamped,
                languageId = language,
                nodeKind = node.kind.id,
                nodeRange = node.range,
                nodeText = if (truncated) text.subSequence(0, MAX_NODE_TEXT).toString() else text.toString(),
                nodeTextTruncated = truncated,
                ancestors = ancestors,
            )
        }
    }
}
