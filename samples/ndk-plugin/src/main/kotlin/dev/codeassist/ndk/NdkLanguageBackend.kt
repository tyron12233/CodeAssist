package dev.codeassist.ndk

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
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.platform.log.Logger
import dev.ide.vfs.VirtualFile
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * The C/C++ language backend: today, the seam semantic highlighting comes in through.
 *
 * The editor asks a file's backend for semantic tokens, and C and C++ had none, so they only ever had the
 * lexer's colours. This claims both languages with a deliberately thin analyzer -- the buffer as one node,
 * like a plain-text file, no resolution of its own -- whose highlighter asks the resident clang
 * ([ClangDaemon]) to classify every identifier in the file it already holds parsed. That is compiler-accurate
 * (a field is a field because clang resolved it, not because it follows a `.`) and nearly free: the editor
 * asks for errors and tokens on the same buffer back to back, and the second is answered from the first
 * one's parse.
 *
 * It is also where the plugin's own parser will plug in (outline, folding, navigation); until then every
 * other service is absent and the host's defaults apply.
 */
class NdkLanguageBackend(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : LanguageBackend {
    override val id = "ndk"
    override val languages = setOf(LanguageId(NdkPlugin.C_LANGUAGE), LanguageId(NdkPlugin.CPP_LANGUAGE))
    override val capabilities = setOf(BackendCapability.ERROR_RECOVERY, BackendCapability.SEMANTIC_HIGHLIGHT)

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = NdkSourceAnalyzer(toolchain, log)
}

internal class NdkSourceAnalyzer(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : SourceAnalyzer {

    /** The last buffer handed to [incrementalParser] per file: the host refreshes it before asking for tokens. */
    private val buffers = ConcurrentHashMap<String, CharSequence>()

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
            buffers[snapshot.file.path] = snapshot.text
            return TextFile(snapshot.file, snapshot.version, snapshot.text)
        }

        override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult {
            val tree = parseFull(newSnapshot)
            return ReparseResult(tree, tree.range, reusedSubtrees = 0)
        }
    }

    override val semanticHighlighter: SemanticHighlightService = object : SemanticHighlightService {
        override suspend fun highlight(file: VirtualFile): List<SemanticToken> {
            val text = buffers[file.path]?.toString() ?: return emptyList()
            val tc = toolchain() ?: return emptyList()
            if (tc.prepare() !is NdkToolchain.Status.Ready) return emptyList()
            val path = runCatching { Paths.get(file.path) }.getOrNull() ?: return emptyList()
            val cpp = NdkPlugin.CPP_SUFFIXES.any { path.fileName.toString().endsWith(it) }
            // The same flags as the error check, so both requests hit one translation unit in the daemon (a
            // different set would be a second parse of its own).
            val facet = NdkFlags.facetFor(path) ?: NdkFacet()
            val result = tc.semanticTokens(path, text, cpp, NdkFlags.editor(facet, tc, cpp)) ?: return emptyList()
            return runCatching { ClangSemanticTokens.parse(result.output, text) }
                .onFailure { log.warn("could not read clang's semantic tokens", it) }
                .getOrDefault(emptyList())
        }
    }

    override val inlayHints: InlayHintService? = null
    override val signatureHelp: SignatureHelpService? = null
    override val folding: FoldingService? = null
    override val formatting: FormattingService? = null
    override val importOrganizer: ImportOrganizerService? = null

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        TextFile(file, 0, buffers[file.path] ?: "")

    override suspend fun analyze(file: VirtualFile): AnalysisResult = AnalysisResult(file, emptyList())
    override fun resolve(node: DomNode): ResolveResult = ResolveResult.Unresolved
    override fun scopeAt(file: VirtualFile, offset: Int): Scope = EmptyScope
    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null

    /** The whole buffer as one node: what the DOM contract asks of a backend with no parser of its own yet. */
    private class TextFile(
        override val file: VirtualFile,
        override val documentVersion: Long,
        private val text: CharSequence,
    ) : ParsedFile {
        override val kind: NodeKind = NodeKind("ndk.file")
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

/**
 * Reads the daemon's `tokens` answer: `<byte offset> <byte length> <kind> <modifiers>` per line.
 *
 * clang counts in UTF-8 bytes and the editor in UTF-16 chars, which agree only while the file is ASCII. A
 * comment with an accent before an identifier would otherwise shift every token after it, so the offsets are
 * converted by walking the text once (the daemon emits tokens in file order).
 */
object ClangSemanticTokens {

    /** The colour attribute macros are drawn with; registered by the plugin's UI facet. */
    const val MACRO_KIND = "cppMacro"

    fun parse(output: String, text: String): List<SemanticToken> {
        val tokens = ArrayList<SemanticToken>()
        val offsets = ByteToChar(text)
        for (line in output.lineSequence()) {
            if (line.isEmpty()) continue
            val parts = line.split(' ')
            if (parts.size != 4) continue
            val byteStart = parts[0].toIntOrNull() ?: continue
            val byteLength = parts[1].toIntOrNull() ?: continue
            val start = offsets.charAt(byteStart) ?: continue
            val end = offsets.charAt(byteStart + byteLength) ?: continue
            if (end <= start) continue
            tokens += SemanticToken(TextRange(start, end), HighlightKind(parts[2]), modifiers(parts[3]))
        }
        return tokens
    }

    private fun modifiers(code: String): Set<HighlightModifier> {
        if (code == "-") return emptySet()
        val set = HashSet<HighlightModifier>(code.length)
        for (c in code) when (c) {
            'D' -> set += HighlightModifier.DECLARATION
            'S' -> set += HighlightModifier.STATIC
            'R' -> set += HighlightModifier.READONLY
            'X' -> set += HighlightModifier.DEPRECATED
        }
        return set
    }

    /**
     * UTF-8 byte offsets to UTF-16 char offsets, for offsets asked in increasing order (rewinding when not).
     * Null for an offset past the end or inside a multi-byte character.
     */
    private class ByteToChar(private val text: String) {
        private var charIndex = 0
        private var byteIndex = 0

        fun charAt(byteOffset: Int): Int? {
            if (byteOffset < byteIndex) { charIndex = 0; byteIndex = 0 }
            while (byteIndex < byteOffset) {
                if (charIndex >= text.length) return null
                val c = text[charIndex]
                if (Character.isHighSurrogate(c) && charIndex + 1 < text.length && Character.isLowSurrogate(text[charIndex + 1])) {
                    byteIndex += 4; charIndex += 2
                } else {
                    byteIndex += when {
                        c.code < 0x80 -> 1
                        c.code < 0x800 -> 2
                        else -> 3
                    }
                    charIndex++
                }
            }
            return if (byteIndex == byteOffset) charIndex else null
        }
    }
}
