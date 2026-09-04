package dev.ide.lang

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
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.Module
import dev.ide.model.LanguageLevel
import dev.ide.model.Workspace
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
interface LanguageBackend : LanguageScoped {
    val id: String                          // "jdt" | "kotlin" | "xml" | "custom"

    /** The languages this backend claims. Unlike a cross-cutting [LanguageScoped] extension, a backend
     *  claiming nothing is never selected: it must name what it parses. */
    override val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>

    /** Editor-time: tolerant parsing, resolution, completion. */
    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer
}

@JvmInline
value class LanguageId(val id: String)

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
    ORGANIZE_IMPORTS,   // provides an ImportOrganizerService (the "Optimize Imports" command)
}

/**
 * Built FROM the project model. [classpath] is the same hashed ClasspathSnapshot the build uses, so
 * api/implementation correctness and cache-invalidation-on-classpath-change are inherited, not
 * re-derived. Changing this context's fingerprint invalidates the analyzer's caches.
 */
interface CompilationContext {
    val sourceRoots: List<VirtualFile>

    /**
     * The dependency path, JVM-shaped by default because that is what the host can derive from the model on
     * its own. A language with no classpath leaves it [ClasspathSnapshot.EMPTY] and carries what its own
     * toolchain needs as an [attribute], or as entries under a [dev.ide.model.ClasspathEntryKind] of its own.
     */
    val classpath: ClasspathSnapshot get() = ClasspathSnapshot.EMPTY

    /** The platform SDK's path. [ClasspathSnapshot.EMPTY] for a language with no boot classpath. */
    val bootClasspath: ClasspathSnapshot get() = ClasspathSnapshot.EMPTY

    /** Defaults to [LanguageLevel.DEFAULT]; a language with its own versioning names its own level. */
    val languageLevel: LanguageLevel get() = LanguageLevel.DEFAULT

    /** Where compilation output lands, or null for a language that produces none. */
    val outputDir: VirtualFile? get() = null

    /** JVM annotation processors. Empty for everything that has no such notion. */
    val processors: List<AnnotationProcessor> get() = emptyList()

    /**
     * A language-specific input the core has no name for: an interpreter path, a virtualenv, include
     * directories, a sysroot. The provider that built this context is what puts one here, and the backend
     * that reads it is the same plugin, so the two agree on the key without the core knowing it exists.
     * Answers null for a key this context carries no value for, which is the normal case.
     */
    fun <T : Any> attribute(key: ContextKey<T>): T? = null

    /**
     * Source attachments for the classpath libraries (e.g. Maven `-sources.jar`s). NOT compiled — they exist
     * so editor features can recover parameter names and javadoc that the binary classes don't carry. Empty
     * by default; the JDK `src.zip` and Android platform sources are derived by the backend from the boot
     * classpath rather than listed here.
     */
    val sourceAttachments: List<VirtualFile> get() = emptyList()
}

/**
 * Typed key for a [CompilationContext.attribute]. Like [dev.ide.model.FacetKey] it has **reference
 * identity**, so the plugin that writes an attribute and the backend that reads it must name the same `val`;
 * [id] exists for diagnostics, not for matching.
 */
class ContextKey<T : Any>(val id: String) {
    override fun toString(): String = id
}

/**
 * Builds the [CompilationContext] for a module whose analysis inputs the host cannot derive.
 *
 * Without one, every backend is handed the context the host assembles from the project model, which is the
 * JVM reading of a module: a classpath walked with `api`/`implementation` export semantics, a platform SDK
 * boot classpath, a Java language level. That is right for the languages the IDE ships and wrong for a
 * language whose inputs are a virtualenv, an include path or a sysroot, which no amount of model-walking
 * produces.
 *
 * The host asks each provider claiming the language, in registration order, and uses the first non-null
 * answer; returning null means "not mine after all", and falls back to the model-derived context. A provider
 * is free to start from that context and add to it, which is the usual case for a language that does have a
 * classpath but needs something extra alongside it.
 */
interface CompilationContextProvider : LanguageScoped {
    /**
     * The context to analyze [module] in [language] with, or null to leave it to the host. [variant] is the
     * active build-variant config-name set, as passed to [dev.ide.model.Module.classpath]. Must not mutate
     * the model, and is called on the analysis dispatcher, so it should not block on the network.
     */
    fun contextFor(
        workspace: Workspace,
        module: Module,
        language: LanguageId,
        variant: Set<String>?,
    ): CompilationContext?
}

/** Plugins contribute context providers here; the host consults them before its own model-derived context. */
val COMPILATION_CONTEXT_PROVIDER_EP =
    ExtensionPoint<CompilationContextProvider>("platform.compilationContext")

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
}
