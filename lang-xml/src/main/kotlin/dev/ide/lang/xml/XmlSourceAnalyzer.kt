package dev.ide.lang.xml

import dev.ide.lang.AnalysisResult
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.xml.completion.XmlCompletionContributor
import dev.ide.lang.xml.completion.XmlCompletion
import dev.ide.lang.xml.completion.XmlCompletionPosition
import dev.ide.lang.xml.folding.XmlFoldingService
import dev.ide.lang.xml.highlight.XmlSemanticHighlighter
import dev.ide.lang.xml.hints.XmlInlayHintService
import dev.ide.lang.xml.hints.XmlResourceValueResolver
import dev.ide.lang.xml.signature.XmlSignatureHelp
import dev.ide.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The editor-time engine for XML. Tolerant parsing + completion are live; symbol resolution is not
 * modeled (XML has no Java-style scope), so [resolve]/[scopeAt]/[expectedTypeAt] return empty
 * results. Well-formedness diagnostics come straight from the parser and are surfaced via [analyze].
 *
 * [contributors] is the injection seam the host fills (e.g. an Android widget + resource contributor),
 * mirroring how the JDT analyzer receives its index service. The cached last-parse per file lets the
 * host's "parseFull then analyze" call sequence return the diagnostics from that exact parse.
 */
class XmlSourceAnalyzer : SourceAnalyzer {

    /** Set by the host after construction. Empty ⇒ completion returns nothing (no Android knowledge here). */
    @Volatile
    var contributors: List<XmlCompletionContributor> = emptyList()

    /** Set by the host: resolves a local `@type/name` to its value for inlay hints. Null ⇒ no inlay hints. */
    @Volatile
    var inlayResourceResolver: XmlResourceValueResolver? = null

    /** Set by the host: describes an attribute's expected value (enum/flags/boolean/refs) for signature help
     *  when the caret is inside `="…"`. Null ⇒ no parameter hints (no Android schema wired). */
    @Volatile
    var valueHintProvider: ((XmlCompletionPosition) -> SignatureInfo?)? = null

    private val backing = XmlIncrementalParser()
    private val lastByFile = ConcurrentHashMap<String, ParsedFile>()

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile =
            backing.parseFull(snapshot).also { lastByFile[snapshot.file.path] = it }

        override fun reparse(
            previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>
        ): ReparseResult = backing.reparse(previous, newSnapshot, edits)
            .also { lastByFile[newSnapshot.file.path] = it.tree }
    }

    private val completionContributor = XmlCompletion(contributors = { contributors })
    override fun completionContributions(): List<CompletionContribution> =
        listOf(CompletionContribution(completionContributor))

    /** Type-aware coloring is structural over the DOM (namespace prefixes + resource references), always on. */
    override val semanticHighlighter = XmlSemanticHighlighter(::parsedFile)

    /** Code folding (element bodies + comments), always on — ranges come straight from the PSI. */
    override val folding: FoldingService = XmlFoldingService(::parsedFile)

    /** Parameter hints inside an attribute value — available only once the host wires the schema resolver. */
    override val signatureHelp: SignatureHelpService?
        get() = valueHintProvider?.let {
            XmlSignatureHelp({ ds -> incrementalParser.parseFull(ds) }, it)
        }

    /** Inlay hints (resolved resource-value previews) are available only once the host wires the resolver. */
    override val inlayHints: InlayHintService?
        get() = inlayResourceResolver?.let { XmlInlayHintService(::parsedFile, it) }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        lastByFile[file.path] ?: incrementalParser.parseFull(EmptyDocument(file))

    override suspend fun analyze(file: VirtualFile): AnalysisResult {
        val parsed = lastByFile[file.path]
        return AnalysisResult(file, parsed?.diagnostics ?: emptyList())
    }

    override fun resolve(node: DomNode): ResolveResult = ResolveResult.Unresolved
    override fun scopeAt(file: VirtualFile, offset: Int): Scope = EmptyScope
    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null

    private class EmptyDocument(override val file: VirtualFile) : DocumentSnapshot {
        override val version: Long = 0
        override val text: CharSequence = ""
        override fun length(): Int = 0
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }
}
