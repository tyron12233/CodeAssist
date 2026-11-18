package dev.codeassist.csharp.editor


import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsDocText
import dev.codeassist.csharp.completion.CsSemanticCompletion
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
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.imports.ImportOrganizerService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Minimal C# backend that lets the host create parsed files and analysis targets.
 *
 * It represents each source file as one node containing the entire buffer. It does not parse
 * syntax or resolve symbols itself: parameter help, the colouring of names and quick documentation are
 * answered by Roslyn through [CsSemanticCompletion].
 * Without a backend the host builds no parsed tree for a `.cs` file, so the file gets no analysis
 * target and the diagnostic provider is never asked about it.
 */
class CsLanguageBackend internal constructor(
    private val signatureHelp: SignatureHelpService? = null,
    private val semantic: CsSemanticCompletion? = null,
) : LanguageBackend {

    override val id = "csharp"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))
    override val capabilities = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.SIGNATURE_HELP,
        BackendCapability.SEMANTIC_HIGHLIGHT,
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer =
        CsSourceAnalyzer(signatureHelp, semantic)
}

internal class CsSourceAnalyzer(
    override val signatureHelp: SignatureHelpService? = null,
    private val semantic: CsSemanticCompletion? = null,
) : SourceAnalyzer {

    /** The last buffer handed to [incrementalParser] per file: the host refreshes it before asking for tokens. */
    private val buffers = ConcurrentHashMap<String, CharSequence>()

    // Parsing

    override val incrementalParser: IncrementalParser = object : IncrementalParser {

        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            buffers[snapshot.file.path] = snapshot.text
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
    /**
     * Colours each name by what Roslyn resolves it to: a type, a method, a parameter, a local. No answer
     * (the server is not up, the file is in no project) leaves the editor's keyword colouring as it is.
     */
    override val semanticHighlighter: SemanticHighlightService? = semantic?.let { service ->
        object : SemanticHighlightService {
            override suspend fun highlight(file: VirtualFile): List<SemanticToken> {
                val text = buffers[file.path]?.toString() ?: return emptyList()
                val tokens = try {
                    service.tokensFor(file.path, text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return emptyList()

                return tokens.mapNotNull { token ->
                    if (token.start + token.length > text.length) return@mapNotNull null
                    SemanticToken(
                        TextRange(token.start, token.start + token.length),
                        HighlightKind(token.kind),
                        token.modifiers.mapNotNullTo(HashSet()) { MODIFIERS[it] },
                    )
                }
            }
        }
    }

    /** The signature and summary of the symbol at [offset], from Roslyn. */
    override fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? {
        val service = semantic ?: return null
        val hover = runCatching {
            runBlocking { service.hoverAt(file.path, text.toString(), offset) }
        }.getOrNull() ?: return null

        return QuickDocInfo(
            signature = hover.signature,
            name = hover.name,
            kind = KINDS[hover.kind] ?: SymbolKind.FIELD,
            container = hover.container.ifEmpty { null },
            doc = CsDocText.summary(hover.documentation),
            docFormat = DocFormat.PLAIN,
        )
    }
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

    private companion object {
        val MODIFIERS = mapOf(
            "declaration" to HighlightModifier.DECLARATION,
            "static" to HighlightModifier.STATIC,
            "abstract" to HighlightModifier.ABSTRACT,
            "deprecated" to HighlightModifier.DEPRECATED,
            "readonly" to HighlightModifier.READONLY,
            "extension" to HighlightModifier.EXTENSION,
        )

        /** The server's word for a symbol, as the kind quick documentation is headed with. */
        val KINDS = mapOf(
            "namespace" to SymbolKind.PACKAGE,
            "class" to SymbolKind.CLASS,
            "interface" to SymbolKind.INTERFACE,
            "enum" to SymbolKind.ENUM,
            "typeParameter" to SymbolKind.TYPE_PARAMETER,
            "method" to SymbolKind.METHOD,
            "function" to SymbolKind.METHOD,
            "constructor" to SymbolKind.CONSTRUCTOR,
            "property" to SymbolKind.FIELD,
            "field" to SymbolKind.FIELD,
            "constant" to SymbolKind.FIELD,
            "enumConstant" to SymbolKind.ENUM_CONSTANT,
            "parameter" to SymbolKind.PARAMETER,
            "localVariable" to SymbolKind.LOCAL_VARIABLE,
        )
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null

        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()

        override fun resolve(name: String): ResolveResult =
            ResolveResult.Unresolved
    }
}
