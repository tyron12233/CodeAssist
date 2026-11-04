package dev.ide.lsp.client

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.DECLARATION_PROVIDER_EP
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.DeclarationProvider
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.NAVIGATION_PROVIDER_EP
import dev.ide.analysis.NavigationKind
import dev.ide.analysis.NavigationProvider
import dev.ide.analysis.NavigationTarget
import dev.ide.analysis.QuickFix
import dev.ide.analysis.REFERENCE_PROVIDER_EP
import dev.ide.analysis.RENAME_PROVIDER_EP
import dev.ide.analysis.ReferenceProvider
import dev.ide.analysis.RenameProvider
import dev.ide.analysis.RenameResult
import dev.ide.analysis.RenameTarget
import dev.ide.analysis.SymbolReference
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.AnalysisResult
import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.highlight.UsageAccess
import dev.ide.lang.highlight.UsageHighlight
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
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
import dev.ide.lang.resolve.StructureItem
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.ParameterInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.template.DefaultSnippetEngine
import dev.ide.lang.template.SnippetContext
import dev.ide.lang.template.SnippetTemplate
import dev.ide.lang.template.SnippetVariableResolver
import dev.ide.lang.template.lineIndentAt
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.platform.Disposable
import dev.ide.platform.EngineCancellation
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.vfs.VirtualFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * One [LanguageServerDefinition] as the IDE's own language features: a [LanguageBackend] (semantic colours,
 * folding, formatting, signature help, inlay hints, hover, structure) plus the providers the engine asks
 * separately (diagnostics, completion, code actions, declaration / implementation / type navigation, usages,
 * rename). [register] puts them all on the registry under the plugin that contributed the definition.
 *
 * Every entry point blocks with a budget rather than suspending (see the module's build file for why) and
 * polls the engine's cancel token while it waits. A feature the server did not advertise answers empty at once.
 */
class LspLanguageSupport(
    val definition: LanguageServerDefinition,
    private val manager: LspSessionManager,
) {
    private val languages: Set<LanguageId> = definition.languages

    /** The last buffer the host parsed per path: the services that are handed only a file read it from here. */
    private val buffers = ConcurrentHashMap<String, CharSequence>()

    /** The diagnostics last returned per document, for the code-action request's `context.diagnostics`. */
    private val lastDiagnostics = ConcurrentHashMap<String, LspSession.Diagnostics>()

    /** documentSymbol answers per path, keyed by the text they were computed for (breadcrumbs ask per caret move). */
    private val structureCache = ConcurrentHashMap<String, Pair<String, List<StructureItem>>>()

    /**
     * Register every adapter. [withBackend] is false when another backend already claims one of [languages]
     * (a plugin with its own parser that uses the server only for diagnostics and navigation): the backend
     * is then the plugin's, and only the providers the engine asks separately are added.
     */
    fun register(registry: ExtensionRegistry, plugin: PluginId, withBackend: Boolean): List<Disposable> = buildList {
        servesBackend = withBackend
        if (withBackend) add(registry.register(LANGUAGE_BACKEND_EP, backend, plugin))
        add(registry.register(DIAGNOSTIC_PROVIDER_EP, diagnostics, plugin))
        add(registry.register(COMPLETION_CONTRIBUTOR_EP, CompletionContribution(completion, languages = languages), plugin))
        add(registry.register(ACTION_PROVIDER_EP, codeActions, plugin))
        add(registry.register(DECLARATION_PROVIDER_EP, declarations, plugin))
        add(registry.register(NAVIGATION_PROVIDER_EP, navigation, plugin))
        add(registry.register(REFERENCE_PROVIDER_EP, references, plugin))
        add(registry.register(RENAME_PROVIDER_EP, rename, plugin))
    }

    // ---------------------------------------------------------------------------------------------- plumbing

    /**
     * What a wait polls to give up early: the engine's cancel token on the engine thread, or the caller's own
     * check while a [prefetch] runs on another thread.
     */
    private val cancelOverride = ThreadLocal<(() -> Unit)?>()
    private val cancel: () -> Unit = { cancelOverride.get()?.invoke() ?: EngineCancellation.checkCanceled() }

    /**
     * Answers already computed for one version of a document, so the engine's call after a [prefetch] is a
     * lookup. Keyed by session, document, version and what was asked (and where); a newer version or a
     * restarted session never matches an old entry. Small, and least-recently-used out.
     */
    private val answers = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > MAX_CACHED_ANSWERS
    }

    /**
     * The questions being put to a server right now, by [answers] key. The breadcrumb and the structure view
     * both prefetch the outline when a document changes, and without this each asked the server for it.
     */
    private val inFlight = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> answer(session: LspSession, doc: LspSession.Document, what: String, compute: () -> T?): T? {
        val key = "${System.identityHashCode(session)}|${doc.uri}|${doc.version}|$what"
        while (true) {
            synchronized(answers) { answers[key] }?.let { return it as T }
            val mine = java.util.concurrent.CompletableFuture<Any>()
            val running = inFlight.putIfAbsent(key, mine)
            if (running == null) {
                val value = try {
                    compute()
                } catch (t: Throwable) {
                    inFlight.remove(key, mine)
                    mine.completeExceptionally(t) // a waiter asks for itself: this caller may only have been cancelled
                    throw t
                }
                if (value != null) synchronized(answers) { answers[key] = value }
                inFlight.remove(key, mine)
                mine.complete(value ?: NO_ANSWER)
                return value
            }
            // The same question is already out: wait for its answer instead of asking the server twice.
            val shared = try {
                awaitShared(running)
            } catch (_: java.util.concurrent.ExecutionException) {
                continue // that caller gave up or failed; ask again (or wait on whoever asks next)
            }
            return if (shared === NO_ANSWER) null else shared as T
        }
    }

    /** [future]'s value, polling [cancel] meanwhile so a waiting engine call can still be preempted. */
    private fun awaitShared(future: java.util.concurrent.CompletableFuture<Any>): Any {
        while (true) {
            cancel()
            try {
                return future.get(SHARED_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // keep waiting
            }
        }
    }

    /** The LSP passes [prefetch] can run ahead of the engine. */
    enum class Pass { DIAGNOSTICS, SEMANTIC, FOLDS, INLAY, STRUCTURE, HOVER, SIGNATURE, COMPLETION }

    /**
     * Do [pass]'s server round trip for [path] at [text] now, on the calling thread, so the engine's own call for
     * the same text finds the answer ready instead of holding the engine thread while the server works. Call it
     * off the engine thread, just before queuing the engine call. [offset] is the caret for hover, signature help
     * and completion; [range] the visible range for inlay hints. [isActive] going false gives up the wait.
     */
    fun prefetch(pass: Pass, path: Path, text: CharSequence, offset: Int = 0, range: TextRange? = null, isActive: () -> Boolean = { true }) {
        cancelOverride.set { if (!isActive()) throw java.util.concurrent.CancellationException("prefetch abandoned") }
        try {
            val normalized = path.toAbsolutePath().normalize()
            when (pass) {
                Pass.DIAGNOSTICS -> {
                    val session = readySession(normalized) ?: return
                    session.diagnostics(normalized, text, Budgets.DIAGNOSTICS, cancel)?.let { lastDiagnostics[it.document.uri] = it }
                }
                Pass.SEMANTIC -> if (servesBackend) semanticTokensFor(normalized, text)
                Pass.FOLDS -> if (servesBackend) foldsFor(normalized, text)
                Pass.INLAY -> if (servesBackend) inlayHintsFor(normalized, text, range ?: TextRange(0, text.length))
                Pass.STRUCTURE -> if (servesBackend) structureFor(normalized, text)
                Pass.HOVER -> if (servesBackend) hoverFor(normalized, text, offset)
                Pass.SIGNATURE -> if (servesBackend) signatureFor(normalized, text, offset, SignatureHelpTrigger.CursorUpdate)
                Pass.COMPLETION -> {
                    val (session, doc) = synced(normalized, text, "completionProvider") ?: return
                    completionResponse(session, doc, offset, null)
                }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // The editor moved on; the engine's call (if it still comes) asks again.
        } finally {
            cancelOverride.remove()
        }
    }

    /** Whether this support's backend is the one the engine uses for its languages (see [register]). */
    @Volatile var servesBackend = true
        private set

    private fun pathOf(file: VirtualFile): Path? = runCatching { Paths.get(file.path).toAbsolutePath().normalize() }.getOrNull()

    /** The session for [path], once it is ready (waiting at most [Budgets.STARTUP] for the handshake). */
    private fun readySession(path: Path): LspSession? {
        val session = manager.sessionFor(definition, path) ?: return null
        if (session.awaitReady(Budgets.STARTUP, cancel)) return session
        session.noteAskedWhileStarting(path)
        return null
    }

    /** The session for [path] with [text] sent, when it advertises [provider]. */
    private fun synced(path: Path, text: CharSequence, provider: String): Pair<LspSession, LspSession.Document>? {
        val session = readySession(path) ?: return null
        if (!session.supports(provider)) return null
        val doc = session.sync(path, text) ?: return null
        return session to doc
    }

    /** The text a server's positions in [path] are in: its copy of an open document, else the disk. */
    private fun textOf(session: LspSession, path: Path): String? = session.document(path)?.text ?: LspConvert.readText(path)

    private fun position(doc: LspSession.Document, offset: Int): JsonObject = buildJsonObject {
        put("textDocument", textDocument(doc.uri))
        put("position", doc.lines.position(offset))
    }

    // ---------------------------------------------------------------------------------------------- backend

    val backend: LanguageBackend = object : LanguageBackend {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages
        override val capabilities: Set<BackendCapability> = setOf(
            BackendCapability.ERROR_RECOVERY, BackendCapability.COMPLETION, BackendCapability.SNIPPETS,
            BackendCapability.SEMANTIC_HIGHLIGHT, BackendCapability.CODE_FOLDING, BackendCapability.FORMAT,
            BackendCapability.SIGNATURE_HELP, BackendCapability.INLAY_HINTS, BackendCapability.ORGANIZE_IMPORTS,
        )

        override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = analyzer
    }

    private val analyzer: SourceAnalyzer = object : SourceAnalyzer {
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
            override suspend fun highlight(file: VirtualFile): List<SemanticToken> = this@LspLanguageSupport.semanticTokens(file)
        }
        override val folding: FoldingService = object : FoldingService {
            override suspend fun folds(file: VirtualFile): List<FoldRegion> = this@LspLanguageSupport.folds(file)
        }
        override val formatting: FormattingService = object : FormattingService {
            override suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit> =
                format(file, text, null, style)

            override suspend fun formatRange(file: VirtualFile, text: CharSequence, range: TextRange, style: FormatStyle): List<DocumentEdit> =
                format(file, text, range, style)
        }
        override val signatureHelp: SignatureHelpService = object : SignatureHelpService {
            override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? = this@LspLanguageSupport.signatureHelp(request)
        }
        override val inlayHints: InlayHintService = object : InlayHintService {
            override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> = this@LspLanguageSupport.inlayHints(file, range)
        }
        override val importOrganizer: ImportOrganizerService = object : ImportOrganizerService {
            override suspend fun organizeImports(file: VirtualFile, text: CharSequence): List<DocumentEdit> = this@LspLanguageSupport.organizeImports(file, text)
        }

        override suspend fun parsedFile(file: VirtualFile): ParsedFile = TextFile(file, 0, buffers[file.path] ?: "")
        override suspend fun analyze(file: VirtualFile): AnalysisResult = AnalysisResult(file, emptyList())
        override fun resolve(node: DomNode): ResolveResult = ResolveResult.Unresolved
        override fun scopeAt(file: VirtualFile, offset: Int): Scope = EmptyScope
        override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null
        override fun fileStructure(file: VirtualFile, text: CharSequence): List<StructureItem> = structure(file, text)
        override fun quickDoc(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? = hover(file, text, offset)
    }

    /** The last full token array per document, with the session and result id a delta request builds on. */
    private class TokenCache(val session: LspSession, val resultId: String, val data: IntArray)
    private val tokenCache = ConcurrentHashMap<String, TokenCache>()

    private fun semanticTokens(file: VirtualFile): List<SemanticToken> {
        val path = pathOf(file) ?: return emptyList()
        val text = buffers[file.path] ?: return emptyList()
        return semanticTokensFor(path, text)
    }

    private fun semanticTokensFor(path: Path, text: CharSequence): List<SemanticToken> {
        val (session, doc) = synced(path, text, "semanticTokensProvider") ?: return emptyList()
        val provider = session.capabilities.obj("semanticTokensProvider") ?: return emptyList()
        if (!provider.capability("full")) return emptyList()
        val legend = provider.obj("legend") ?: return emptyList()
        val types = legend.arr("tokenTypes")?.mapNotNull(LspConvert::string).orEmpty()
        val modifiers = legend.arr("tokenModifiers")?.mapNotNull(LspConvert::string).orEmpty()
        return answer(session, doc, "semantic") {
            tokenData(session, doc, provider)?.let { LspConvert.semanticTokens(it, types, modifiers, doc.lines) }
        }.orEmpty()
    }

    /**
     * The document's token array: from `full/delta` against the previous answer when the server offers deltas
     * and there is one from this session, else from `full`. A delta that cannot be applied falls back to `full`.
     */
    private fun tokenData(session: LspSession, doc: LspSession.Document, provider: JsonObject): IntArray? {
        val previous = tokenCache[doc.uri]?.takeIf { it.session === session }
        val deltas = provider.obj("full")?.bool("delta") == true
        if (deltas && previous != null) {
            val result = session.request("textDocument/semanticTokens/full/delta", buildJsonObject {
                put("textDocument", textDocument(doc.uri))
                put("previousResultId", previous.resultId)
            }, Budgets.SEMANTIC, cancel).obj()
            val applied = result?.let { r ->
                r.arr("data")?.let(LspConvert::ints) ?: r.arr("edits")?.let { LspConvert.applyTokenEdits(previous.data, it) }
            }
            if (applied != null) {
                result.str("resultId")?.let { tokenCache[doc.uri] = TokenCache(session, it, applied) } ?: tokenCache.remove(doc.uri)
                return applied
            }
        }
        val result = session.request(
            "textDocument/semanticTokens/full", buildJsonObject { put("textDocument", textDocument(doc.uri)) },
            Budgets.SEMANTIC, cancel,
        ).obj() ?: return null
        val data = LspConvert.ints(result.arr("data") ?: return null)
        result.str("resultId")?.let { tokenCache[doc.uri] = TokenCache(session, it, data) } ?: tokenCache.remove(doc.uri)
        return data
    }

    private fun folds(file: VirtualFile): List<FoldRegion> {
        val path = pathOf(file) ?: return emptyList()
        val text = buffers[file.path] ?: return emptyList()
        return foldsFor(path, text)
    }

    private fun foldsFor(path: Path, text: CharSequence): List<FoldRegion> {
        val (session, doc) = synced(path, text, "foldingRangeProvider") ?: return emptyList()
        return answer(session, doc, "folds") {
            session.request(
                "textDocument/foldingRange", buildJsonObject { put("textDocument", textDocument(doc.uri)) }, Budgets.FOLDING, cancel,
            )?.let { LspConvert.folds(it, doc.lines) }
        }.orEmpty()
    }

    private fun format(file: VirtualFile, text: CharSequence, range: TextRange?, style: FormatStyle): List<DocumentEdit> {
        val path = pathOf(file) ?: return emptyList()
        val session = readySession(path) ?: return emptyList()
        val ranged = range != null && session.supports("documentRangeFormattingProvider")
        if (!ranged && !session.supports("documentFormattingProvider")) return emptyList()
        val doc = session.sync(path, text) ?: return emptyList()
        val result = session.request(
            if (ranged) "textDocument/rangeFormatting" else "textDocument/formatting",
            buildJsonObject {
                put("textDocument", textDocument(doc.uri))
                if (ranged) put("range", doc.lines.range(range!!))
                putJsonObject("options") {
                    put("tabSize", style.indentSize)
                    put("insertSpaces", !style.useTabs)
                }
            },
            Budgets.FORMAT, cancel,
        )
        return LspConvert.textEdits(result.arr(), doc.lines)
    }

    private fun organizeImports(file: VirtualFile, text: CharSequence): List<DocumentEdit> {
        val path = pathOf(file) ?: return emptyList()
        val (session, doc) = synced(path, text, "codeActionProvider") ?: return emptyList()
        val actions = session.request("textDocument/codeAction", buildJsonObject {
            put("textDocument", textDocument(doc.uri))
            put("range", doc.lines.range(TextRange(0, 0)))
            putJsonObject("context") {
                putJsonArray("diagnostics") {}
                putJsonArray("only") { add("source.organizeImports") }
            }
        }, Budgets.CODE_ACTION, cancel).arr() ?: return emptyList()
        val action = actions.firstNotNullOfOrNull { it.obj()?.takeIf { a -> a.str("kind")?.startsWith("source.organizeImports") == true } }
            ?: return emptyList()
        val edit = LspCodeAction(session, action, doc.uri).edits(file) { p -> textOf(session, p) }
        return edit.edits.entries.firstOrNull { LspConvert.samePath(it.key.path, path) }?.value.orEmpty()
    }

    private fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? {
        val path = pathOf(request.document.file) ?: return null
        return signatureFor(path, request.document.text, request.offset, request.trigger)
    }

    private fun signatureFor(path: Path, text: CharSequence, offset: Int, trigger: SignatureHelpTrigger): SignatureHelp? {
        val (session, doc) = synced(path, text, "signatureHelpProvider") ?: return null
        val what = "signature@$offset/" + when (trigger) {
            is SignatureHelpTrigger.TypedChar -> "typed${trigger.char}"
            SignatureHelpTrigger.CursorUpdate -> "cursor"
            else -> "explicit"
        }
        return answer(session, doc, what) { signatureRequest(session, doc, offset, trigger) }
    }

    private fun signatureRequest(session: LspSession, doc: LspSession.Document, offset: Int, trigger: SignatureHelpTrigger): SignatureHelp? {
        val result = session.request("textDocument/signatureHelp", buildJsonObject {
            put("textDocument", textDocument(doc.uri))
            put("position", doc.lines.position(offset))
            putJsonObject("context") {
                when (val t = trigger) {
                    is SignatureHelpTrigger.TypedChar -> { put("triggerKind", 2); put("triggerCharacter", t.char.toString()) }
                    SignatureHelpTrigger.CursorUpdate -> put("triggerKind", 3)
                    else -> put("triggerKind", 1)
                }
                put("isRetrigger", false)
            }
        }, Budgets.SIGNATURE, cancel).obj() ?: return null
        val signatures = result.arr("signatures")?.mapNotNull { e ->
            val s = e.obj() ?: return@mapNotNull null
            val label = s.str("label") ?: return@mapNotNull null
            val params = s.arr("parameters")?.mapNotNull { pe ->
                val p = pe.obj() ?: return@mapNotNull null
                val (start, end, text) = when (val l = p["label"]) {
                    is JsonArray -> {
                        val a = (l.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: -1
                        val b = (l.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: -1
                        Triple(a, b, if (a in 0..b && b <= label.length) label.substring(a, b) else "")
                    }
                    is JsonPrimitive -> {
                        val t = l.contentOrNull.orEmpty()
                        val at = label.indexOf(t)
                        Triple(at, if (at >= 0) at + t.length else -1, t)
                    }
                    else -> Triple(-1, -1, "")
                }
                ParameterInfo(text, start, end, markupText(p["documentation"]))
            }.orEmpty()
            SignatureInfo(label, params, markupText(s["documentation"]), s.int("activeParameter"))
        }.orEmpty()
        if (signatures.isEmpty()) return null
        val active = (result.int("activeSignature") ?: 0).coerceIn(0, signatures.size - 1)
        return SignatureHelp(signatures, active, result.int("activeParameter") ?: signatures[active].activeParameter ?: 0)
    }

    private fun inlayHints(file: VirtualFile, range: TextRange): List<InlayHint> {
        val path = pathOf(file) ?: return emptyList()
        val text = buffers[file.path] ?: return emptyList()
        return inlayHintsFor(path, text, range)
    }

    private fun inlayHintsFor(path: Path, text: CharSequence, range: TextRange): List<InlayHint> {
        val (session, doc) = synced(path, text, "inlayHintProvider") ?: return emptyList()
        val clamped = TextRange(range.start.coerceIn(0, text.length), range.end.coerceIn(0, text.length))
        return answer(session, doc, "inlay@${clamped.start}-${clamped.end}") { inlayRequest(session, doc, clamped) }.orEmpty()
    }

    private fun inlayRequest(session: LspSession, doc: LspSession.Document, clamped: TextRange): List<InlayHint>? {
        val result = session.request("textDocument/inlayHint", buildJsonObject {
            put("textDocument", textDocument(doc.uri))
            put("range", doc.lines.range(clamped))
        }, Budgets.INLAY, cancel).arr() ?: return null
        return result.mapNotNull { e ->
            val h = e.obj() ?: return@mapNotNull null
            val offset = doc.lines.toOffset(h["position"]) ?: return@mapNotNull null
            val parts = when (val label = h["label"]) {
                is JsonPrimitive -> listOf(InlayHintPart(label.contentOrNull.orEmpty()))
                is JsonArray -> label.mapNotNull { p -> p.obj()?.str("value")?.let(::InlayHintPart) }
                else -> emptyList()
            }.filter { it.text.isNotEmpty() }
            if (parts.isEmpty()) return@mapNotNull null
            InlayHint(
                offset = offset,
                parts = parts,
                kind = when (h.int("kind")) {
                    1 -> InlayHintKind.TYPE
                    2 -> InlayHintKind.PARAMETER
                    else -> InlayHintKind.OTHER
                },
                tooltip = markupText(h["tooltip"]),
                paddingLeft = h.bool("paddingLeft") == true,
                paddingRight = h.bool("paddingRight") == true,
            )
        }
    }

    private fun structure(file: VirtualFile, text: CharSequence): List<StructureItem> {
        val path = pathOf(file) ?: return emptyList()
        return structureFor(path, text)
    }

    private fun structureFor(path: Path, text: CharSequence): List<StructureItem> {
        val content = text.toString()
        val key = path.toString()
        structureCache[key]?.let { (forText, items) -> if (forText == content) return items }
        val (session, doc) = synced(path, content, "documentSymbolProvider") ?: return emptyList()
        // Through [answer], so the breadcrumb and the structure view asking at once share one request.
        val items = answer(session, doc, "structure") {
            session.request(
                "textDocument/documentSymbol", buildJsonObject { put("textDocument", textDocument(doc.uri)) }, Budgets.STRUCTURE, cancel,
            )?.let { LspConvert.structure(it, doc.lines) }
        } ?: return emptyList()
        return items.also { structureCache[key] = content to it }
    }

    private fun hover(file: VirtualFile, text: CharSequence, offset: Int): QuickDocInfo? {
        val path = pathOf(file) ?: return null
        return hoverFor(path, text, offset)
    }

    private fun hoverFor(path: Path, text: CharSequence, offset: Int): QuickDocInfo? {
        val (session, doc) = synced(path, text, "hoverProvider") ?: return null
        val result = answer(session, doc, "hover@$offset") {
            session.request("textDocument/hover", position(doc, offset), Budgets.HOVER, cancel).obj()
        } ?: return null
        val markdown = markupText(result["contents"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val (signature, body) = splitHover(markdown)
        val name = LspConvert.wordAt(text, offset)?.let { text.substring(it.start, it.end) } ?: signature.take(40)
        return QuickDocInfo(
            signature = signature,
            name = name,
            kind = guessKind(signature),
            container = null,
            doc = body?.takeIf { it.isNotBlank() },
            docFormat = DocFormat.MARKDOWN,
        )
    }

    /** A hover's first fenced code block (or first line) as the signature, the rest as the documentation. */
    private fun splitHover(markdown: String): Pair<String, String?> {
        val fence = Regex("(?s)^\\s*```[^\\n]*\\n(.*?)\\n?```\\s*").find(markdown)
        if (fence != null) return fence.groupValues[1].trim() to markdown.substring(fence.range.last + 1).trim().ifEmpty { null }
        val firstLine = markdown.substringBefore('\n').trim()
        return firstLine to markdown.substringAfter('\n', "").trim().ifEmpty { null }
    }

    private fun guessKind(signature: String): SymbolKind {
        val s = signature.trimStart()
        return when {
            Regex("^(abstract\\s+|sealed\\s+|final\\s+|base\\s+)*(class|struct|record|mixin)\\b").containsMatchIn(s) -> SymbolKind.CLASS
            s.startsWith("interface ") || s.startsWith("protocol ") || s.startsWith("trait ") -> SymbolKind.INTERFACE
            s.startsWith("enum ") -> SymbolKind.ENUM
            s.startsWith("package ") || s.startsWith("module ") || s.startsWith("namespace ") -> SymbolKind.PACKAGE
            '(' in s -> SymbolKind.METHOD
            else -> SymbolKind.FIELD
        }
    }

    // ---------------------------------------------------------------------------------------------- diagnostics

    val diagnostics: DiagnosticProvider = object : DiagnosticProvider {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
            val path = pathOf(target.file) ?: return emptyList()
            val session = readySession(path) ?: return emptyList()
            val check: () -> Unit = { target.checkCanceled() }
            val result = session.diagnostics(path, target.parsed.text(), Budgets.DIAGNOSTICS, check) ?: return emptyList()
            lastDiagnostics[result.document.uri] = result
            return LspConvert.diagnostics(result.items, result.lines)
        }
    }

    // ---------------------------------------------------------------------------------------------- completion

    private val snippets = DefaultSnippetEngine()
    private val noVariables = object : SnippetVariableResolver {
        override fun resolve(name: String, ctx: SnippetContext): String? = null
    }

    val completion: CompletionContributor = object : CompletionContributor {
        override val id: String = "lsp:${definition.id}"

        override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
            val path = pathOf(params.document.file) ?: return
            val text = params.document.text
            val (session, doc) = synced(path, text, "completionProvider") ?: return
            val typed = (params.trigger as? CompletionTrigger.TypedChar)?.char
            val response = completionResponse(session, doc, params.offset, typed) ?: return
            val (items, incomplete, defaults) = when (response) {
                is JsonArray -> Triple(response.toList(), false, null)
                is JsonObject -> Triple(response.arr("items")?.toList().orEmpty(), response.bool("isIncomplete") == true, response.obj("itemDefaults"))
                else -> return
            }
            if (incomplete) result.markIncomplete()
            val sorted = completionOrder(items.mapNotNull { it.obj() }, params.prefix)
            var replacement: TextRange? = null
            var added = 0
            for ((rank, item) in sorted.withIndex()) {
                val label = item.str("label") ?: continue
                val filter = item.str("filterText") ?: label
                if (params.prefix.isNotEmpty() && !params.prefixMatches(filter) && !params.prefixMatches(label)) continue
                if (added >= MAX_COMPLETION_ITEMS) { result.markIncomplete(); break }
                val converted = completionItem(item, label, rank, defaults, doc, text, params) { range ->
                    if (replacement == null) replacement = range
                    replacement!!
                } ?: continue
                result.addElement(converted)
                added++
            }
            replacement?.let { result.setReplacementRange(it) }
        }
    }

    /** The server's completion answer at [offset] (cached per document version), triggered by [typed] if a character. */
    private fun completionResponse(session: LspSession, doc: LspSession.Document, offset: Int, typed: Char?): JsonElement? {
        val triggers = session.capabilities.obj("completionProvider")?.arr("triggerCharacters")
            ?.mapNotNull(LspConvert::string).orEmpty()
        val trigger = typed?.toString()?.takeIf { it in triggers }
        return answer(session, doc, "completion@$offset/${trigger ?: ""}") {
            session.request("textDocument/completion", buildJsonObject {
                put("textDocument", textDocument(doc.uri))
                put("position", doc.lines.position(offset))
                putJsonObject("context") {
                    if (trigger != null) { put("triggerKind", 2); put("triggerCharacter", trigger) } else put("triggerKind", 1)
                }
            }, Budgets.COMPLETION, cancel)
        }
    }

    private fun completionItem(
        item: JsonObject,
        label: String,
        rank: Int,
        defaults: JsonObject?,
        doc: LspSession.Document,
        text: CharSequence,
        params: CompletionParams,
        replacementFor: (TextRange) -> TextRange,
    ): CompletionItem? {
        val lines = doc.lines
        val textEdit = item.obj("textEdit")
        val editRange = textEdit?.let { lines.toRange(it["range"] ?: it["insert"]) }
            ?: defaults?.get("editRange")?.let { r -> lines.toRange(r.obj()?.get("insert") ?: r) }
        var newText = textEdit?.str("newText") ?: item.str("textEditText") ?: item.str("insertText") ?: label
        val replacement = editRange?.let(replacementFor) ?: params.replacementRange
        // One replacement range serves every item; an item whose own range starts later keeps the text between.
        if (editRange != null && editRange.start > replacement.start && editRange.start <= text.length) {
            newText = text.substring(replacement.start, editRange.start) + newText
        }
        val format = item.int("insertTextFormat") ?: defaults?.int("insertTextFormat") ?: 1
        var caret: CaretAction = CaretAction.AtEnd
        if (format == 2 && ('$' in newText)) {
            val expansion = snippets.expand(
                SnippetTemplate(newText),
                SnippetContext(params.document, replacement.start, indent = lineIndentAt(text, replacement.start)),
                noVariables,
            )
            newText = expansion.text
            caret = CaretAction.ExpandSnippet(expansion)
        } else if (format == 2) {
            newText = newText.replace("\\}", "}").replace("\\\\", "\\")
        }
        val labelDetails = item.obj("labelDetails")
        val deprecated = item.bool("deprecated") == true ||
            item.arr("tags")?.any { (it as? JsonPrimitive)?.intOrNull == 1 } == true
        return CompletionItem(
            label = label,
            insertText = newText,
            kind = LspConvert.completionKind(item.int("kind")),
            detail = labelDetails?.str("detail")?.let { d -> listOfNotNull(d, item.str("detail")).joinToString("  ") } ?: item.str("detail"),
            container = labelDetails?.str("description"),
            documentation = markupText(item["documentation"]),
            sortPriority = rank + 1,
            additionalEdits = item.arr("additionalTextEdits")?.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                TextEdit(lines.toRange(o["range"]) ?: return@mapNotNull null, o.str("newText") ?: "")
            }.orEmpty(),
            caret = caret,
            relevance = if (deprecated) CompletionRelevance(deprecated = true) else null,
        )
    }

    // ---------------------------------------------------------------------------------------------- code actions

    val codeActions: ActionProvider = object : ActionProvider {
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override fun actions(ctx: EditorActionContext): List<QuickFix> {
            val path = pathOf(ctx.target.file) ?: return emptyList()
            val (session, doc) = synced(path, ctx.target.parsed.text(), "codeActionProvider") ?: return emptyList()
            val range = ctx.range
            val known = lastDiagnostics[doc.uri]?.takeIf { it.document.text == doc.text }
            val overlapping = known?.items?.filter { d ->
                val r = known.lines.toRange(d.obj()?.get("range")) ?: return@filter false
                r.start <= range.end && range.start <= r.end
            }.orEmpty()
            val result = session.request("textDocument/codeAction", buildJsonObject {
                put("textDocument", textDocument(doc.uri))
                put("range", doc.lines.range(range))
                putJsonObject("context") {
                    put("diagnostics", JsonArray(overlapping))
                    put("triggerKind", 1)
                }
            }, Budgets.CODE_ACTION, cancel).arr() ?: return emptyList()
            return result.mapNotNull { e ->
                val action = e.obj() ?: return@mapNotNull null
                if (action["disabled"] != null) return@mapNotNull null
                val title = action.str("title") ?: return@mapNotNull null
                val kind = action.str("kind").orEmpty()
                LspQuickFix(
                    title,
                    when {
                        kind.startsWith("quickfix") -> CodeActionKind.QUICK_FIX
                        kind.startsWith("refactor") -> CodeActionKind.REFACTOR
                        // A bare Command (no kind) offered against a diagnostic is a fix; otherwise an intention.
                        kind.isEmpty() && overlapping.isNotEmpty() -> CodeActionKind.QUICK_FIX
                        else -> CodeActionKind.INTENTION
                    },
                    LspCodeAction(session, action, doc.uri),
                )
            }
        }
    }

    private inner class LspQuickFix(
        override val title: String,
        override val kind: CodeActionKind,
        private val action: LspCodeAction,
    ) : QuickFix {
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit =
            action.edits(ctx.target.file) { p -> textOf(action.session, p) }
    }

    /** One code action (or bare command) and how to turn it into edits: resolve it, then run its command. */
    internal inner class LspCodeAction(val session: LspSession, private val action: JsonObject, private val uri: String) {
        fun edits(focal: VirtualFile, textOf: (Path) -> String?): WorkspaceEdit {
            var resolved = action
            val resolvable = session.capabilities.obj("codeActionProvider")?.bool("resolveProvider") == true
            if (resolved["edit"] == null && resolvable && action["command"] !is JsonPrimitive) {
                session.request("codeAction/resolve", action, Budgets.CODE_ACTION, cancel).obj()?.let { resolved = it }
            }
            var edit = LspConvert.workspaceEdit(resolved.obj("edit"), focal, textOf)
            // A Command is either the action itself (`command` is a string) or nested in it.
            val command = if (resolved["command"] is JsonPrimitive) resolved else resolved.obj("command")
            if (command != null) {
                val name = command.str("command")
                if (name != null) {
                    val (_, captured) = session.captureEdits {
                        session.request("workspace/executeCommand", buildJsonObject {
                            put("command", name)
                            command["arguments"]?.let { put("arguments", it) }
                        }, Budgets.COMMAND, cancel)
                    }
                    for (e in captured) edit += LspConvert.workspaceEdit(e, focal, textOf)
                }
            }
            return edit
        }
    }

    // ---------------------------------------------------------------------------------------------- navigation

    private fun navigate(target: AnalysisTarget, offset: Int, method: String, provider: String): List<NavigationTarget> {
        val path = pathOf(target.file) ?: return emptyList()
        val (session, doc) = synced(path, target.parsed.text(), provider) ?: return emptyList()
        val result = session.request(method, position(doc, offset), Budgets.NAVIGATION, cancel)
        return LspConvert.locations(result) { p -> textOf(session, p) }
    }

    val declarations: DeclarationProvider = object : DeclarationProvider {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override suspend fun declarations(target: AnalysisTarget, offset: Int): List<NavigationTarget> =
            navigate(target, offset, "textDocument/definition", "definitionProvider")
                .ifEmpty { navigate(target, offset, "textDocument/declaration", "declarationProvider") }
    }

    val navigation: NavigationProvider = object : NavigationProvider {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override suspend fun targets(target: AnalysisTarget, offset: Int, kind: NavigationKind): List<NavigationTarget> = when (kind) {
            NavigationKind.IMPLEMENTATION -> navigate(target, offset, "textDocument/implementation", "implementationProvider")
            NavigationKind.TYPE_DECLARATION -> navigate(target, offset, "textDocument/typeDefinition", "typeDefinitionProvider")
        }
    }

    val references: ReferenceProvider = object : ReferenceProvider {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override suspend fun references(target: AnalysisTarget, offset: Int, includeDeclaration: Boolean): List<SymbolReference> {
            val path = pathOf(target.file) ?: return emptyList()
            val (session, doc) = synced(path, target.parsed.text(), "referencesProvider") ?: return emptyList()
            val result = session.request("textDocument/references", buildJsonObject {
                put("textDocument", textDocument(doc.uri))
                put("position", doc.lines.position(offset))
                putJsonObject("context") { put("includeDeclaration", includeDeclaration) }
            }, Budgets.REFERENCES, cancel)
            return LspConvert.ranges(result) { p -> textOf(session, p) }.map { (p, r) -> SymbolReference(p.toString(), r) }
        }

        override suspend fun highlights(target: AnalysisTarget, offset: Int): List<UsageHighlight>? {
            val path = pathOf(target.file) ?: return null
            val (session, doc) = synced(path, target.parsed.text(), "documentHighlightProvider") ?: return null
            val result = session.request("textDocument/documentHighlight", position(doc, offset), Budgets.HIGHLIGHT, cancel)
                ?: return null
            return result.arr()?.mapNotNull { e ->
                val h = e.obj() ?: return@mapNotNull null
                val range = doc.lines.toRange(h["range"]) ?: return@mapNotNull null
                UsageHighlight(range, if (h.int("kind") == 3) UsageAccess.WRITE else UsageAccess.READ)
            }
        }
    }

    val rename: RenameProvider = object : RenameProvider {
        override val id: String = "lsp:${definition.id}"
        override val languages: Set<LanguageId> = this@LspLanguageSupport.languages

        override suspend fun prepare(target: AnalysisTarget, offset: Int): RenameTarget? {
            val path = pathOf(target.file) ?: return null
            val text = target.parsed.text()
            val (session, doc) = synced(path, text, "renameProvider") ?: return null
            val word = LspConvert.wordAt(text, offset)
            val prepares = session.capabilities.obj("renameProvider")?.bool("prepareProvider") == true
            if (!prepares) return word?.let { RenameTarget(text.substring(it.start, it.end), it) }
            val result = try {
                session.requestOrThrow("textDocument/prepareRename", position(doc, offset), Budgets.NAVIGATION, cancel)
            } catch (e: ResponseErrorException) {
                return null
            } catch (e: java.util.concurrent.TimeoutException) {
                return word?.let { RenameTarget(text.substring(it.start, it.end), it) }
            } catch (e: ConnectionClosedException) {
                return null
            }
            val o = result.obj() ?: return null
            if (o.bool("defaultBehavior") == true) return word?.let { RenameTarget(text.substring(it.start, it.end), it) }
            val range = doc.lines.toRange(o["range"] ?: o) ?: return null
            val name = o.str("placeholder") ?: text.substring(range.start, range.end)
            return RenameTarget(name, range)
        }

        override suspend fun rename(target: AnalysisTarget, offset: Int, newName: String): RenameResult {
            val path = pathOf(target.file) ?: return RenameResult.rejected("This file is not on disk.")
            val (session, doc) = synced(path, target.parsed.text(), "renameProvider")
                ?: return RenameResult.rejected("${definition.displayName} is not running.")
            val result = try {
                session.requestOrThrow("textDocument/rename", buildJsonObject {
                    put("textDocument", textDocument(doc.uri))
                    put("position", doc.lines.position(offset))
                    put("newName", newName)
                }, Budgets.RENAME, cancel)
            } catch (e: ResponseErrorException) {
                return RenameResult.rejected(e.message ?: "${definition.displayName} could not rename this.")
            } catch (e: java.util.concurrent.TimeoutException) {
                return RenameResult.rejected("${definition.displayName} did not answer in time.")
            } catch (e: ConnectionClosedException) {
                return RenameResult.rejected("${definition.displayName} is not running.")
            }
            val edit = LspConvert.workspaceEdit(result.obj(), target.file) { p -> textOf(session, p) }
            return if (edit.isEmpty) RenameResult.rejected("Nothing to rename here.") else RenameResult.of(edit)
        }
    }

    /** The whole buffer as one node: what the DOM contract asks of a backend whose parser is a server. */
    private class TextFile(
        override val file: VirtualFile,
        override val documentVersion: Long,
        private val text: CharSequence,
    ) : ParsedFile {
        override val kind: NodeKind = NodeKind("lsp.file")
        override val range: TextRange = TextRange(0, text.length)
        override val parent: DomNode? = null
        override val children: List<DomNode> = emptyList()
        override val diagnostics: List<dev.ide.lang.dom.Diagnostic> = emptyList()
        override fun text(): CharSequence = text
        override fun nodeAt(offset: Int): DomNode = this
        override fun nodesIn(range: TextRange): Sequence<DomNode> = sequenceOf(this)
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }

    /** How long each kind of request may hold the engine thread, in milliseconds. */
    internal object Budgets {
        /** For the handshake, on the first request after a project opens; a later one finds it done. */
        const val STARTUP = 1_500L
        const val DIAGNOSTICS = 2_500L
        const val COMPLETION = 3_000L
        const val HOVER = 2_000L
        const val SIGNATURE = 1_500L
        const val SEMANTIC = 4_000L
        const val FOLDING = 2_000L
        const val STRUCTURE = 2_000L
        const val INLAY = 2_000L
        const val FORMAT = 5_000L
        const val CODE_ACTION = 3_000L
        const val COMMAND = 15_000L
        const val NAVIGATION = 4_000L
        const val HIGHLIGHT = 1_500L
        const val REFERENCES = 10_000L
        const val RENAME = 15_000L
    }

    companion object {
        const val MAX_COMPLETION_ITEMS = 1_000

        /** Answers kept for [prefetch]: a few files' worth of passes, the next one in a row reuses the slot. */
        private const val MAX_CACHED_ANSWERS = 64

        /** How often a caller waiting on another's identical request checks whether it was cancelled. */
        private const val SHARED_POLL_MS = 20L

        /** What a shared request completes with when the server's answer was "nothing". */
        private val NO_ANSWER = Any()

        /**
         * Completion items in the order to offer them: by `sortText`, which is the server's ranking, and among
         * items the server ranked equal, by how well they match the typed [prefix]. Dart gives every type
         * matching `Str` the same sortText, and kept in the server's order `String` came after `StringBuffer`,
         * `StringSink` and `Stream`. Ties go to an exact match, then a case-sensitive prefix match, then a
         * case-insensitive one, then the shorter label, then the label alphabetically (as VS Code orders ties).
         * With nothing typed yet the server's order stands.
         */
        internal fun completionOrder(items: List<JsonObject>, prefix: String): List<JsonObject> {
            val byServer = compareBy<JsonObject> { it.str("sortText") ?: it.str("label") ?: "" }
            if (prefix.isEmpty()) return items.sortedWith(byServer)
            fun tier(item: JsonObject): Int {
                val name = item.str("filterText") ?: item.str("label") ?: return 4
                return when {
                    name == prefix -> 0
                    name.startsWith(prefix) -> 1
                    name.startsWith(prefix, ignoreCase = true) -> 2
                    else -> 3
                }
            }
            return items.sortedWith(
                byServer.thenBy(::tier).thenBy { it.str("label")?.length ?: Int.MAX_VALUE }.thenBy { it.str("label") ?: "" },
            )
        }
    }
}
