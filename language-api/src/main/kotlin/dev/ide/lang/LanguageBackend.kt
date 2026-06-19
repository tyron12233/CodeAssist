package dev.ide.lang

import dev.ide.platform.ExtensionPoint
import dev.ide.lang.completion.CompletionService
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
 * language-api — the seam for "own parser / javac / Eclipse JDT". The core never depends on a
 * concrete parser; it depends on [LanguageBackend] and the backend-neutral DOM/resolve/completion
 * types. The project model supplies the [CompilationContext] (roots + classpath + language level);
 * the backend produces ASTs, diagnostics, resolution, completion, and (for builds) class output.
 *
 * Recommended wiring: JDT is the default analyzer + completion backend (error recovery, working-copy
 * reconcile, a built-in completion engine, light on ART) and a valid compiler; javac is an optional
 * compile backend; a custom parser slots into the same interfaces.
 */
interface LanguageBackend {
    val id: String                          // "jdt" | "javac" | "custom"
    val languages: Set<LanguageId>
    val capabilities: Set<BackendCapability>

    /** Editor-time: tolerant parsing, resolution, completion. */
    fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer

    /** Build-time: emit .class. Null if this backend can analyze but not compile. */
    fun createCompiler(ctx: CompilationContext): SourceCompiler?
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
    COMPLETION,         // provides a CompletionService
    SNIPPETS,           // completion emits snippet items (CaretAction.ExpandSnippet)
    POSTFIX,            // contributes/handles postfix templates (dev.ide.lang.postfix)
    INLAY_HINTS,        // provides an InlayHintService
    SEMANTIC_HIGHLIGHT, // provides a SemanticHighlightService (type-aware editor coloring)
    COMPILE,            // can emit bytecode
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
    val completion: CompletionService?      // null if !capabilities.contains(COMPLETION)
    val inlayHints: InlayHintService?       // null if !capabilities.contains(INLAY_HINTS)
        get() = null

    /** Type-aware editor coloring; null if !capabilities.contains(SEMANTIC_HIGHLIGHT). See the highlight SPI. */
    val semanticHighlighter: dev.ide.lang.highlight.SemanticHighlightService?
        get() = null

    /** Current tolerant tree for [file] (parsed/incrementally maintained). */
    suspend fun parsedFile(file: VirtualFile): ParsedFile

    /** Diagnostics + (partial) bindings for [file]. */
    suspend fun analyze(file: VirtualFile): AnalysisResult

    // --- the three things the AST must support, surfaced directly ---

    /** Resolve a reference node to a symbol. */
    fun resolve(node: DomNode): ResolveResult

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

/** Build-time compilation. Consumes the same [CompilationContext]; emits class files to outputDir. */
interface SourceCompiler {
    suspend fun compile(sources: List<VirtualFile>): CompileResult
}

data class CompileResult(
    val success: Boolean,
    val diagnostics: List<Diagnostic>,
    val outputClasses: List<VirtualFile>,
)
