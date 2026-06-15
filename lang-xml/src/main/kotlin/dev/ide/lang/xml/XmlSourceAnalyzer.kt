package dev.ide.lang.xml

import dev.ide.lang.AnalysisResult
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.xml.completion.XmlCompletionContributor
import dev.ide.lang.xml.completion.XmlCompletionService
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
    @Volatile var contributors: List<XmlCompletionContributor> = emptyList()

    private val backing = XmlIncrementalParser()
    private val lastByFile = ConcurrentHashMap<String, ParsedFile>()

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile =
            backing.parseFull(snapshot).also { lastByFile[snapshot.file.path] = it }

        override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult =
            backing.reparse(previous, newSnapshot, edits).also { lastByFile[newSnapshot.file.path] = it.tree }
    }

    override val completion: CompletionService = XmlCompletionService(contributors = { contributors })

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
