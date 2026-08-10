package dev.ide.core

import dev.ide.lang.AnalysisResult
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.vfs.VirtualFile

/** The language a file gets when no registered [dev.ide.lang.LanguageBackend] claims its file type. */
internal val PLAIN_TEXT_LANGUAGE = LanguageId("text")

private val TEXT_NODE = NodeKind("text")

/**
 * The no-op analyzer for a file no language backend claims: a `res/raw/` data file, a `.txt`/`.json`/`.csv`
 * asset, Markdown, ProGuard keep rules, any unregistered extension. It parses to ONE text node and offers no
 * diagnostics, no completion contributions, and no editor services, so such a file is edited as plain text.
 *
 * It is the fallback because the alternative was the JAVA analyzer: a `res/raw/notes.txt` (which resolves to a
 * module through that module's `res/` root) was parsed and compiled as Java, so its whole content came back as
 * syntax and unresolved-symbol errors.
 */
internal object PlainTextAnalyzer : SourceAnalyzer {

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile =
            TextFile(snapshot.file, snapshot.version, snapshot.text)

        override fun reparse(
            previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>
        ): ReparseResult {
            val tree = parseFull(newSnapshot)
            return ReparseResult(tree, tree.range, reusedSubtrees = 0)
        }
    }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile = TextFile(file, 0, "")

    override suspend fun analyze(file: VirtualFile): AnalysisResult = AnalysisResult(file, emptyList())

    override fun resolve(node: DomNode): ResolveResult = ResolveResult.Unresolved
    override fun scopeAt(file: VirtualFile, offset: Int): Scope = EmptyScope
    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null

    /** The whole buffer as a single leaf node: enough to satisfy the DOM contract without a parser. */
    private class TextFile(
        override val file: VirtualFile,
        override val documentVersion: Long,
        private val text: CharSequence,
    ) : ParsedFile {
        override val kind: NodeKind = TEXT_NODE
        override val range: TextRange = TextRange(0, text.length)
        override val parent: DomNode? = null
        override val children: List<DomNode> = emptyList()
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun text(): CharSequence = text
        override fun nodeAt(offset: Int): DomNode = this
        override fun nodesIn(range: TextRange): Sequence<DomNode> = sequenceOf(this)
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }
}
