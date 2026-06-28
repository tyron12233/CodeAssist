package dev.ide.lang

import dev.ide.platform.ExtensionPoint
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.dom.DomNode
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.vfs.VirtualFile

/**
 * language-api — the seam for "own parser / Eclipse JDT / a custom frontend". The core never depends on a
 * concrete parser; it depends on [LanguageBackend] and the backend-neutral DOM/resolve/completion types.
 * The project model supplies the [CompilationContext] (roots + classpath + language level); the backend
 * produces ASTs, diagnostics, resolution, and completion.
 *
 * **Editor-side only.** Emitting bytecode is the build system's job: each language module owns its own
 * build compile task (lang-jdt drives ecj, lang-kotlin drives K2) and the build graph calls it directly.
 * The LanguageBackend is therefore not a compiler factory; it never sees the build.
 *
 * Recommended wiring: JDT is the default analyzer + completion backend (error recovery, working-copy
 * reconcile, a built-in completion engine, light on ART); a custom parser slots into the same interfaces.
 */
interface LanguageBackend {
    val id: String                          // "jdt" | "kotlin" | "xml" | "custom"
    val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>

    /** Editor-time: tolerant parsing, resolution, completion. */
    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer
}

@JvmInline value class LanguageId(val id: String)

/**
 * The extension point through which language backends are contributed. The host (ide-core) selects a
 * backend per file by matching the file's [LanguageId] against each backend's [LanguageBackend.languages],
 * so a new language (XML today, Kotlin later) is added by registering one more backend — not by editing
 * the host. Backends register in plugin order; the host picks the first whose `languages` contains the id.
 */
val LANGUAGE_BACKEND_EP = ExtensionPoint<LanguageBackend>("platform.languageBackend")

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
}

/**
 * Built FROM the project model. [classpath] is the same hashed ClasspathSnapshot the build uses, so
 * api/implementation correctness and cache-invalidation-on-classpath-change are inherited, not
 * re-derived. Changing this context's fingerprint invalidates the analyzer's caches.
 */
interface CompilationContext {
    val sourceRoots: List<VirtualFile>
    val classpath: ClasspathSnapshot
    val bootClasspath: ClasspathSnapshot
    val languageLevel: LanguageLevel
    val outputDir: VirtualFile
    val processors: List<AnnotationProcessor>

    /**
     * Source attachments for the classpath libraries (e.g. Maven `-sources.jar`s). NOT compiled — they exist
     * so editor features can recover parameter names and javadoc that the binary classes don't carry. Empty
     * by default; the JDK `src.zip` and Android platform sources are derived by the backend from the boot
     * classpath rather than listed here.
     */
    val sourceAttachments: List<VirtualFile> get() = emptyList()
}

interface AnnotationProcessor {
    val qualifiedName: String
    val classpath: ClasspathSnapshot
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
    fun completionContributions(): List<dev.ide.lang.completion.CompletionContribution> = emptyList()

    /** Parameter-info popup; null if !capabilities.contains(SIGNATURE_HELP). See the signature SPI. */
    val signatureHelp: dev.ide.lang.signature.SignatureHelpService?
        get() = null

    /** Type-aware editor coloring; null if !capabilities.contains(SEMANTIC_HIGHLIGHT). See the highlight SPI. */
    val semanticHighlighter: dev.ide.lang.highlight.SemanticHighlightService?
        get() = null

    /** Code-folding regions; null if !capabilities.contains(CODE_FOLDING). See the folding SPI. */
    val folding: dev.ide.lang.folding.FoldingService?
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
    fun fileStructure(file: VirtualFile, text: CharSequence): List<dev.ide.lang.resolve.StructureItem> = emptyList()

    /**
     * Quick documentation (signature + doc comment) for the symbol at [offset] in [file]'s live buffer [text],
     * or null when nothing resolves there. Default null; a backend that resolves symbols overrides it.
     */
    fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): dev.ide.lang.resolve.QuickDocInfo? = null

    /** Visible names at a position — the candidate set for name-reference completion. */
    fun scopeAt(file: VirtualFile, offset: Int): Scope

    /** Inferred target type at a position, for completion ranking. */
    fun expectedTypeAt(file: VirtualFile, offset: Int): dev.ide.lang.resolve.TypeRef?

    /**
     * The type an expression [node] *produces* — e.g. a method call's return type, a `new`'s class, a
     * literal's type. Distinct from [expectedTypeAt], which is the type the *context* wants. Returns null
     * when [node] isn't a resolvable expression. Used by refactorings such as "introduce variable" to name
     * the declared type instead of `var`. Default null so non-resolving backends needn't implement it.
     */
    fun resolveType(node: DomNode): dev.ide.lang.resolve.TypeRef? = null
}

data class AnalysisResult(val file: VirtualFile, val diagnostics: List<Diagnostic>)
