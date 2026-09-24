package dev.ide.lang

import kotlin.jvm.JvmInline

import dev.ide.lang.completion.CompletionContribution
import dev.ide.platform.ExtensionPoint
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.dom.DomNode
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.imports.ImportOrganizerService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.vfs.VirtualFile

@JvmInline
value class LanguageId(val id: String)

enum class BackendCapability {
    ERROR_RECOVERY,     // produces a usable tree from invalid source (required for editor + completion)
    INCREMENTAL,        // supports reparse() rather than full reparse
    BINDINGS,           // resolves symbols/types (even partially) on broken code
    COMPLETION,         // publishes completion contributors (SourceAnalyzer.completionContributions)
    SNIPPETS,           // completion emits snippet items (CaretAction.ExpandSnippet)
    POSTFIX,            // contributes/handles postfix templates (dev.ide.lang.postfix)
    INLAY_HINTS,        // provides an InlayHintService
    SIGNATURE_HELP,     // provides a SignatureHelpService (parameter-info popup)
    SEMANTIC_HIGHLIGHT, // provides a SemanticHighlightService (type-aware editor coloring)
    CODE_FOLDING,       // provides a FoldingService (collapse imports / blocks / comments)
    FORMAT,
    ORGANIZE_IMPORTS,   // provides an ImportOrganizerService (the "Optimize Imports" command)
}

/**
 * The editor-facing engine. Holds incremental state for the files it analyzes and exposes the DOM,
 * resolution, and completion entry points. A single module may use JDT here for analysis and javac
 * as its compiler — they are independent picks behind one SPI.
 */
interface SourceAnalyzer {
    val incrementalParser: IncrementalParser
    val inlayHints: InlayHintService?       // null if !capabilities.contains(INLAY_HINTS)
        get() = null

    /**
     * Extra completion contributors this analyzer exposes to the unified completion engine, bound to its own
     * resolver / symbol model (so they share the analyzer's state rather than re-resolving). The engine runs
     * them alongside the [completion] service (itself wrapped as a contributor) and any plugin contributors.
     * Empty by default — a backend opts in to publish analyzer-aware contributors (e.g. type-driven postfix
     * or scope-driven keyword logic) as first-class engine contributors. See `dev.ide.lang.completion`.
     */
    fun completionContributions(): List<CompletionContribution> = emptyList()

    /** Parameter-info popup; null if !capabilities.contains(SIGNATURE_HELP). See the signature SPI. */
    val signatureHelp: SignatureHelpService?
        get() = null

    /** Type-aware editor coloring; null if !capabilities.contains(SEMANTIC_HIGHLIGHT). See the highlight SPI. */
    val semanticHighlighter: SemanticHighlightService?
        get() = null

    /** Code-folding regions; null if !capabilities.contains(CODE_FOLDING). See the folding SPI. */
    val folding: FoldingService?
        get() = null

    /** Code reformatting; null if !capabilities.contains(FORMAT). See the formatting SPI. */
    val formatting: FormattingService?
        get() = null

    /** "Optimize Imports"; null if !capabilities.contains(ORGANIZE_IMPORTS). See the imports SPI. */
    val importOrganizer: ImportOrganizerService?
        get() = null

    /** Current tolerant tree for [file] (parsed/incrementally maintained). */
    suspend fun parsedFile(file: VirtualFile): ParsedFile

    /** Diagnostics + (partial) bindings for [file]. */
    suspend fun analyze(file: VirtualFile): AnalysisResult

    // --- the three things the AST must support, surfaced directly ---

    /** Resolve a reference node to a symbol. */
    fun resolve(node: DomNode): ResolveResult

    /**
     * The file's declarations for the structure view / outline + sticky scroll headers, in document order,
     * each with its nesting depth. Empty by default; a backend that can cheaply enumerate declarations
     * (walking its own parse tree) overrides it. [text] is the live buffer so the result matches the editor.
     */
    fun fileStructure(file: VirtualFile, text: CharSequence): List<StructureItem> = emptyList()

    /**
     * Quick documentation (signature + doc comment) for the symbol at [offset] in [file]'s live buffer [text],
     * or null when nothing resolves there. Default null; a backend that resolves symbols overrides it.
     */
    fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? = null

    /** Visible names at a position — the candidate set for name-reference completion. */
    fun scopeAt(file: VirtualFile, offset: Int): Scope

    /** Inferred target type at a position, for completion ranking. */
    fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef?

    /**
     * Drop resolution state this analyzer caches beyond its own parse trees, because something it resolved
     * against changed underneath it. [reason] says what: the host calls this rather than reaching for a
     * concrete analyzer type, so a backend it has never heard of participates in invalidation like the
     * built-ins do.
     *
     * A backend that caches nothing beyond its trees needs no override. A backend that holds a live
     * compiler environment (a name environment, a PSI facade, a binding cache) must drop the matching part:
     * such an environment is deliberately NOT disposed on these events, so that the warm classpath survives,
     * and it will otherwise keep resolving a stale answer.
     */
    fun invalidateCaches(reason: CacheInvalidation) {}

    /**
     * The type an expression [node] *produces* — e.g. a method call's return type, a `new`'s class, a
     * literal's type. Distinct from [expectedTypeAt], which is the type the *context* wants. Returns null
     * when [node] isn't a resolvable expression. Used by refactorings such as "introduce variable" to name
     * the declared type instead of `var`. Default null so non-resolving backends needn't implement it.
     */
    fun resolveType(node: DomNode): TypeRef? = null
}

data class AnalysisResult(val file: VirtualFile, val diagnostics: List<Diagnostic>)

/**
 * Why the host is asking an analyzer to drop cached resolution state (see [SourceAnalyzer.invalidateCaches]).
 * An open set in spirit: a backend ignores a reason it has no cache for.
 */
enum class CacheInvalidation {
    /**
     * The set of synthetic ("light") classes changed, so anything resolved from them is stale. Raised when a
     * [dev.ide.lang.synthetic.SyntheticClassProvider]'s answer changes: an Android resource edit regenerating
     * `R`, a new ViewBinding, a declaration a generator's output depends on.
     */
    SYNTHETIC_CLASSES,

    /**
     * Cross-file bindings are stale: a file this analyzer resolved against was created, deleted, moved, or
     * changed on disk outside the editor. Trees stay valid; what they resolved TO may not.
     */
    BINDINGS,

    /**
     * Nothing is stale, but the system is short of memory: drop every cache that can be rebuilt on demand
     * (parse trees of files not being edited, resolution memos, decoded library symbols). Raised when the IDE
     * leaves the screen or the OS reports memory pressure. The next request pays the rebuild, so a backend
     * keeps what correctness needs and nothing more; it must never tear down state that cannot be recomputed.
     */
    MEMORY_PRESSURE,
}
