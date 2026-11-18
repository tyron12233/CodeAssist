package dev.codeassist.csharp.editor


import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.editor.CsSourceAnalyzer.TextFile
import dev.ide.lang.AnalysisResult
import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.imports.ImportOrganizerService
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
import dev.ide.vfs.VirtualFile

/**
 * Minimal C# backend that lets the host create parsed files and analysis targets.
 *
 * It represents each source file as one node containing the entire buffer. It does not parse
 * syntax or resolve symbols. Signature help is the only optional language service provided.
 * Without a backend the host builds no parsed tree for a `.cs` file, so the file gets no analysis
 * target and the diagnostic provider is never asked about it.
 */
class CsLanguageBackend(
    private val signatureHelp: SignatureHelpService? = null,
) : LanguageBackend {

    override val id = "csharp"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))
    override val capabilities = setOf(BackendCapability.ERROR_RECOVERY, BackendCapability.SIGNATURE_HELP)

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer =
        CsSourceAnalyzer(signatureHelp)
}

internal class CsSourceAnalyzer(
    override val signatureHelp: SignatureHelpService? = null,
) : SourceAnalyzer {

    // Parsing

    override val incrementalParser: IncrementalParser = object : IncrementalParser {

        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            return TextFile(snapshot.file, snapshot.version, snapshot.text)
        }

        override fun reparse(
            previous: ParsedFile,
            newSnapshot: DocumentSnapshot,
            edits: List<DocumentEdit>,
        ): ReparseResult {
            val tree = parseFull(newSnapshot)
            return ReparseResult(tree, tree.range, reusedSubtrees = 0)
        }
    }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile {
        return TextFile(file, 0, "")
    }

    // Optional editor services

    override val inlayHints: InlayHintService? = null
    override val semanticHighlighter: SemanticHighlightService? = null
    override val folding: FoldingService? = null
    override val formatting: FormattingService? = null
    override val importOrganizer: ImportOrganizerService? = null

    // Analysis and resolution

    override suspend fun analyze(file: VirtualFile): AnalysisResult =
        AnalysisResult(file, emptyList())

    override fun resolve(node: DomNode): ResolveResult =
        ResolveResult.Unresolved

    override fun scopeAt(file: VirtualFile, offset: Int): Scope =
        EmptyScope

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null

    // Parsed file

    /** Represents the entire source buffer as a single node. */
    private class TextFile(
        override val file: VirtualFile,
        override val documentVersion: Long,
        private val text: CharSequence,
    ) : ParsedFile {

        override val kind = NodeKind("csharp.file")
        override val range = TextRange(0, text.length)
        override val parent: DomNode? = null
        override val children: List<DomNode> = emptyList()
        override val diagnostics: List<Diagnostic> = emptyList()

        override fun text(): CharSequence = text

        override fun nodeAt(offset: Int): DomNode = this

        override fun nodesIn(range: TextRange): Sequence<DomNode> =
            sequenceOf(this)
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null

        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()

        override fun resolve(name: String): ResolveResult =
            ResolveResult.Unresolved
    }
}
