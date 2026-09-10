package dev.ide.analysis

import dev.ide.index.IndexService
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile

/**
 * The Analyzer SPI: two analyzer shapes over a common base, with the [tier] driving scheduling.
 * Built-ins (the JDT/Kotlin plugins) use the same API as third-party plugins; all register on
 * [ANALYZER_EP].
 */

/**
 * Which event stream feeds an analyzer and how aggressively it runs:
 *  - [SYNTAX]   — DOM only (naming, structure, formatting). Cheapest; effectively every keystroke.
 *  - [SEMANTIC] — needs the [AnalysisTarget.resolver] (types/symbols): unused var, type mismatch, NPE.
 *  - [PROJECT]  — needs the cross-file [IndexService] view: unused public API, duplicate declarations.
 */
enum class AnalyzerTier { SYNTAX, SEMANTIC, PROJECT }

interface Analyzer {
    val id: AnalyzerId
    val displayName: String
    val languages: Set<LanguageId>
    val defaultSeverity: Severity
    val tier: AnalyzerTier
}

/**
 * Runs over one file. The framework performs one shared DOM traversal per file pass and hands each
 * SYNTAX/SEMANTIC analyzer the nodes of the [NodeKind]s it registered interest in: N analyzers, one
 * walk. [analyze] is synchronous: it reads an already-parsed [AnalysisTarget.parsed] and the
 * synchronous resolver, and runs inside the framework's cancellable read action.
 */
interface FileAnalyzer : Analyzer {
    /** `null` = invoked once for the whole file; else the framework only dispatches these node kinds. */
    val interestedIn: Set<NodeKind>?

    /**
     * Analyze [target], reporting to [sink]. The form to implement for a whole-file analyzer, and the
     * fallback for one that names [interestedIn] kinds but has no use for the shared walk.
     */
    fun analyze(target: AnalysisTarget, sink: DiagnosticSink)

    /**
     * Analyze [target] against the pass's shared traversal: [nodes] holds the file's nodes grouped by
     * kind, so an analyzer keyed on [interestedIn] gets the ones it wants without walking the DOM again.
     * Override this instead of the two-argument form whenever the analyzer is node-keyed. The engine
     * always calls this one, and its default delegates to [analyze] for the analyzers that don't care.
     */
    fun analyze(target: AnalysisTarget, sink: DiagnosticSink, nodes: NodeIndex) = analyze(target, sink)
}

/**
 * The file's nodes grouped by [NodeKind]: the one DOM traversal a file pass makes, shared by every
 * [FileAnalyzer] in it (and by the engine's own `interestedIn` gate).
 *
 * The walk is **lazy**: it happens on the first query and not at all for a pass whose analyzers are all
 * whole-file ones, which is the common case (a walk allocates a node wrapper per node, so on a large file
 * it is the single most expensive part of a pass that finds nothing). Built per pass and discarded with
 * it, so nothing here is retained across passes (a node wrapper pins the tree it came from).
 *
 * Not thread-safe, and not meant to be: one pass, one thread, one index.
 */
class NodeIndex private constructor(private val parsed: ParsedFile) {

    private var byKind: Map<NodeKind, List<DomNode>>? = null

    private fun index(): Map<NodeKind, List<DomNode>> = byKind ?: build().also { byKind = it }

    private fun build(): Map<NodeKind, List<DomNode>> {
        val out = HashMap<NodeKind, MutableList<DomNode>>()
        for (node in parsed.nodesIn(parsed.range)) out.getOrPut(node.kind) { ArrayList() }.add(node)
        return out
    }

    /** The file's nodes of [kind], in document order; empty when the file has none. */
    fun nodes(kind: NodeKind): List<DomNode> = index()[kind] ?: emptyList()

    /** Whether the file holds any node of [kind]: the engine's gate, and cheaper than [nodes]. */
    operator fun contains(kind: NodeKind): Boolean = index().containsKey(kind)

    /** Every kind present in the file. */
    val kinds: Set<NodeKind> get() = index().keys

    companion object {
        /** An index over [parsed]; the traversal is deferred to the first query. */
        fun over(parsed: ParsedFile): NodeIndex = NodeIndex(parsed)
    }
}

/**
 * Runs over the project/index and emits diagnostics attributed back to specific files (hence the
 * file-aware [ProjectDiagnosticSink]). Suspending and cancellable because it may pull many files'
 * targets and query the index — the heaviest, lowest-priority tier.
 */
interface ProjectAnalyzer : Analyzer {
    suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink)
}

/** Per-file semantic context for a [FileAnalyzer] / a single file in a project sweep. */
interface AnalysisTarget {
    val file: VirtualFile
    /** The neutral, error-tolerant DOM (always covers the whole file, even mid-edit). */
    val parsed: ParsedFile
    /** The document version [parsed] was built from — the cache/staleness key. */
    val documentVersion: Long
    /** Resolution + types for this file: `resolve(node)`, `scopeAt`, `expectedTypeAt` (language-api). */
    val resolver: SourceAnalyzer
    /** Global lookups for analyzers that need them (e.g. "is this name declared elsewhere?"). */
    val index: IndexService
    val module: Module
    fun checkCanceled()
}

/** The whole-project view for [ProjectAnalyzer]s and batch lint. */
interface ProjectAnalysisScope {
    val modules: List<Module>
    val index: IndexService

    /** Every analyzable source file in scope. */
    fun files(): Sequence<VirtualFile>

    /** The per-file context for [file] (parses/maintains the DOM on demand). */
    suspend fun targetFor(file: VirtualFile): AnalysisTarget

    fun checkCanceled()
}

/**
 * Where a [FileAnalyzer] reports findings — implicitly against [AnalysisTarget.file]. The framework
 * stamps [DiagnosticSource], applies the profile's severity override, and filters suppressions before
 * publishing, so analyzers just describe the problem.
 */
interface DiagnosticSink {
    fun report(
        range: TextRange,
        severity: Severity,
        message: String,
        code: String? = null,
        fixes: List<QuickFix> = emptyList(),
        tags: Set<DiagnosticTag> = emptySet(),
        related: List<RelatedRange> = emptyList(),
    )
}

/** Like [DiagnosticSink] but the report names its target [file] — required for cross-file analyzers. */
interface ProjectDiagnosticSink {
    fun report(
        file: VirtualFile,
        range: TextRange,
        severity: Severity,
        message: String,
        code: String? = null,
        fixes: List<QuickFix> = emptyList(),
        tags: Set<DiagnosticTag> = emptySet(),
        related: List<RelatedRange> = emptyList(),
    )
}
