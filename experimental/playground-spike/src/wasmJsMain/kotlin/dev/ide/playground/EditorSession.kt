// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground

import dev.ide.index.impl.ClasspathIndex
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.kotlin.syntax.KotlinOutline
import dev.ide.lang.dom.TextRange
import dev.ide.lang.dom.expandSelection
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.KotlinEditorFeatures
import dev.ide.lang.kotlin.KotlinFormatter
import dev.ide.lang.kotlin.KotlinImportOrganizer
import dev.ide.lang.kotlin.KotlinSignatureHelpService
import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.IncrementalSemanticAnalysis
import dev.ide.lang.kotlin.KotlinInlayHintService
import dev.ide.lang.kotlin.KotlinLanguage
import dev.ide.lang.kotlin.KotlinSemanticHighlighter
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.KotlinResolverCaches
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.platform.createDirectories
import dev.ide.vfs.VirtualFile
import kotlin.time.TimeSource

/** One completion offered: what to show, what to insert, and any extra edits (an auto-import). */
class CompletionOffer(
    val label: String,
    val insertText: String,
    val kind: String,
    val detail: String?,
    val container: String?,
    /** `[from, to, text]` edits against the ORIGINAL text, applied together with the insertion. */
    val additionalEdits: List<Triple<Int, Int, String>>,
)

class CompletionOutcome(val from: Int, val to: Int, val incomplete: Boolean, val items: List<CompletionOffer>, val millis: Long)

/** A classified identifier: its range, what it is (`class`, `function`, ...) and orthogonal facts (`static`, `extension`, ...). */
class TokenOutcome(val from: Int, val to: Int, val kind: String, val modifiers: List<String>)

/** An inlay hint: [parts] are the label's runs, [kind] is `TYPE`, `PARAMETER`, `CHAINING` or `OTHER`. */
class HintOutcome(
    val offset: Int,
    val parts: List<String>,
    val kind: String,
    val tooltip: String?,
    val paddingLeft: Boolean,
    val paddingRight: Boolean,
)

class DiagnosticOutcome(val from: Int, val to: Int, val severity: String, val message: String, val code: String?)

/**
 * The editor half of the Playground: completion and diagnostics over ONE open file, with no JVM.
 *
 * The same pieces the iOS host wires, because there they are already proven off the JVM: a [ClasspathIndex] over
 * the library jars (which is what turns `println` from resolvable into DISCOVERABLE), a [KotlinSymbolService]
 * over the project's source and those jars, [KotlinCompletion], and [IncrementalSemanticAnalysis].
 *
 * [classpath] names jars already in the in-memory file system: the ones a PROGRAM may use, which for the
 * Playground is the standard library (and coroutines). Not the interpreter's jars: those are the engine, not
 * the user's world, and indexing them would offer the engine's own classes as completions.
 */
class EditorSession(classpath: List<String>) {
    private val index: ClasspathIndex?
    private val service: KotlinSymbolService
    val indexMillis: Long

    init {
        createDirectories(PROJECT_DIR)
        val started = TimeSource.Monotonic.markNow()
        index = ClasspathIndex.build(
            classpath,
            listOf(KotlinCallableIndex, KotlinTypeShapeIndex, KotlinBuiltinsIndex, KotlinBuiltinCallableIndex),
            "/cache/index",
        )
        indexMillis = started.elapsedNow().inWholeMilliseconds
        service = KotlinSymbolService(
            sourceRoots = listOf<VirtualFile>(MemoryFile(PROJECT_DIR)),
            classpathJars = classpath,
            index = index,
            cacheDir = "/cache/kotlin-ext",
        )
    }

    private val completion = KotlinCompletion(service)

    /** Resolver memo caches shared by the passes over one snapshot, so a keystroke's passes pay for inference once. */
    private fun cachesFor(parsed: KotlinParsedFile): KotlinResolverCaches {
        val stamp = service.externalContentStamp(parsed.file.path)
        cachesFor?.let { (p, c) -> if (p === parsed && stampOf == stamp) return c }
        stampOf = stamp
        return KotlinResolverCaches().also { cachesFor = parsed to it }
    }

    private fun resolverFor(parsed: KotlinParsedFile): KotlinResolver =
        KotlinResolver(parsed.ktFile, parsed, service, cachesFor(parsed))

    private val highlighter by lazy {
        KotlinSemanticHighlighter(
            parsedFor = { f -> last?.takeIf { it.file.path == f.path } },
            resolverFor = ::resolverFor,
            refresh = { }, // the host re-asks per keystroke; nothing subscribes to an invalidation
            externalStampFor = { service.externalContentStamp(it) },
        )
    }

    private val inlays by lazy {
        KotlinInlayHintService(parsedFor = { f -> last?.takeIf { it.file.path == f.path } }, resolverFor = ::resolverFor)
    }
    private val analysis = IncrementalSemanticAnalysis(service, resolveReady = { index != null })

    private var version = 0L
    private var last: KotlinParsedFile? = null
    private var cachesFor: Pair<KotlinParsedFile, KotlinResolverCaches>? = null
    private var stampOf = 0L

    private class Buffer(override val file: VirtualFile, private val content: String) : DocumentSnapshot {
        override val version: Long = 1
        override val text: CharSequence get() = content
        override fun length(): Int = content.length
    }

    /** The parse of [text], reused while the text is unchanged (the passes of one keystroke share it). */
    private fun parsed(text: String): KotlinParsedFile {
        last?.let { if (it.ktFile.text.contentEquals(text)) return it }
        val kt = KotlinParserHost.parse(FILE_NAME, text)
        return KotlinParsedFile(kt, MemoryFile(FILE_PATH), ++version).also { last = it }
    }

    private fun sync(text: String): KotlinParsedFile {
        service.setOverlay(mapOf(FILE_PATH to text))
        val p = parsed(text)
        service.syncFocal(p.file.path, p.ktFile.text.hashCode()) { SourceIndexBuilder.extractFrom(p.ktFile, p, p.file.path) }
        return p
    }

    suspend fun complete(text: String, offset: Int): CompletionOutcome {
        val started = TimeSource.Monotonic.markNow()
        sync(text)
        val result = completion.complete(
            CompletionRequest(Buffer(MemoryFile(FILE_PATH), text), offset, CompletionTrigger.Explicit),
            KotlinLanguage.ID,
        )
        return CompletionOutcome(
            from = result.replacementRange.start,
            to = result.replacementRange.end,
            incomplete = result.isIncomplete,
            items = result.items.map {
                CompletionOffer(
                    it.label, it.insertText, it.kind.name, it.detail, it.container,
                    it.additionalEdits.map { e -> Triple(e.range.start, e.range.end, e.newText) },
                )
            },
            millis = started.elapsedNow().inWholeMilliseconds,
        )
    }

    // ---- the rest of the editor's features, answered as JSON by [call] --------------------------------------------

    private val features by lazy {
        KotlinEditorFeatures(
            service = service,
            analysis = analysis,
            parsedFor = { path -> last?.takeIf { it.file.path == path } },
            refreshOverlay = { lastText?.let { service.setOverlay(mapOf(FILE_PATH to it)) } },
            syncFocal = { p -> service.syncFocal(p.file.path, p.ktFile.text.hashCode()) { SourceIndexBuilder.extractFrom(p.ktFile, p, p.file.path) } },
        )
    }
    private val signatures by lazy { KotlinSignatureHelpService(service) }
    private val formatter = KotlinFormatter()
    private val importOrganizer = KotlinImportOrganizer()
    private var lastText: String? = null

    private fun editsJson(edits: List<DocumentEdit>?): String = buildString {
        append('[')
        edits.orEmpty().forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('[').append(e.offset).append(',').append(e.offset + e.oldLength).append(',').append(jsonString(e.newText.toString())).append(']')
        }
        append(']')
    }

    /**
     * One entry point for the features that return a small answer: [method] names it, [start]/[end] are the caret
     * or the selection, and the result is JSON the host maps to its own types (`null` when there is nothing).
     */
    suspend fun call(method: String, text: String, start: Int, end: Int): String {
        val file = MemoryFile(FILE_PATH)
        val parsedFile = sync(text)
        lastText = text
        return when (method) {
            "folds" -> KotlinOutline.folds(text).joinToString(",", "[", "]") {
                "[${it.startOffset},${it.endOffset},${jsonString(it.placeholder)},${jsonString(it.kind)},${it.collapsedByDefault}]"
            }
            "signature" -> signatures.signatureHelp(
                SignatureHelpRequest(Buffer(file, text), start, SignatureHelpTrigger.CursorUpdate),
            )?.let { h ->
                buildString {
                    append("{\"activeSignature\":").append(h.activeSignature).append(",\"activeParameter\":").append(h.activeParameter)
                    append(",\"signatures\":[")
                    h.signatures.forEachIndexed { i, sig ->
                        if (i > 0) append(',')
                        append("{\"label\":").append(jsonString(sig.label)).append(",\"doc\":").append(jsonOrNull(sig.documentation))
                        append(",\"active\":").append(sig.activeParameter ?: -1).append(",\"params\":[")
                        sig.parameters.forEachIndexed { j, p ->
                            if (j > 0) append(',')
                            append('[').append(jsonString(p.label)).append(',').append(p.labelStart).append(',').append(p.labelEnd).append(',').append(p.alreadyNamed).append(']')
                        }
                        append("]}")
                    }
                    append("]}")
                }
            } ?: "null"
            "quickdoc" -> features.quickDoc(file, text, start)?.let {
                "{\"signature\":${jsonString(it.signature)},\"name\":${jsonString(it.name)},\"kind\":${jsonString(it.kind.name.lowercase())}," +
                    "\"container\":${jsonOrNull(it.container)},\"doc\":${jsonOrNull(it.doc)},\"docFormat\":${jsonString(it.docFormat.name.lowercase())}}"
            } ?: "null"
            "format" -> editsJson(
                if (start < 0) formatter.format(file, text, FORMAT_STYLE) else formatter.formatRange(file, text, TextRange(start, end), FORMAT_STYLE),
            )
            "imports" -> editsJson(importOrganizer.organizeImports(file, text))
            // Go to declaration: only a target in THIS file can be opened here; a library class has no source to show.
            "definition" -> features.navigationTargets(file, text, start, NavKind.DECLARATION)
                .firstOrNull { it.file.path == FILE_PATH }?.let { "{\"offset\":${it.offset}}" } ?: "null"
            "expand" -> expandSelection(parsedFile, start, end, text.length)?.let { "[${it.start},${it.end}]" } ?: "null"
            else -> "null"
        }
    }

    suspend fun semanticTokens(text: String): List<TokenOutcome> {
        val p = sync(text)
        return highlighter.highlight(p.file).map {
            TokenOutcome(it.range.start, it.range.end, it.kind.id, it.modifiers.map(HighlightModifier::name))
        }
    }

    suspend fun inlayHints(text: String, start: Int, end: Int): List<HintOutcome> {
        val p = sync(text)
        return inlays.hints(p.file, TextRange(start, end)).map {
            HintOutcome(it.offset, it.parts.map { part -> part.text }, it.kind.name, it.tooltip, it.paddingLeft, it.paddingRight)
        }
    }

    fun diagnose(text: String): List<DiagnosticOutcome> {
        val p = sync(text)
        // The parser's own syntax errors, then the semantic checks over the tree.
        return (p.diagnostics + analysis.diagnostics(p)).map {
            DiagnosticOutcome(it.range.start, it.range.end, it.severity.name, it.message, it.code)
        }
    }

    companion object {
        /** Kotlin official: 4-space indent, 8-space continuation (what `CodeStyleSettings.default("kotlin")` is). */
        private val FORMAT_STYLE = FormatStyle(styleId = "kotlin_official", indentSize = 4, continuationIndent = 8, tabWidth = 4)

        const val PROJECT_DIR = "/project"
        const val FILE_NAME = "Main.kt"
        const val FILE_PATH = "$PROJECT_DIR/$FILE_NAME"
    }
}
