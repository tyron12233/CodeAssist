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
import dev.ide.lang.kotlin.KotlinDiagnosticCodes
import dev.ide.lang.kotlin.KotlinEditorFeatures
import dev.ide.lang.kotlin.KotlinImportFix
import dev.ide.lang.kotlin.KotlinUsageTarget
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
import dev.ide.platform.deleteFile
import dev.ide.platform.fileInfo
import dev.ide.platform.writeFileAtomically
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
    /** Where the caret lands, in characters into [insertText] (`map { | }`); -1 = after it. */
    val caretOffset: Int = -1,
    /** Characters selected from [caretOffset] on (a placeholder to overtype); 0 = just a caret. */
    val caretSelect: Int = 0,
    /** Tab stops to step through after the insertion (a snippet or template item); null for a plain item. */
    val snippet: SnippetOffer? = null,
)

/** A snippet's tab stops: `[index, [[start, end], ...], [choice, ...]]` per stop, offsets into the insert text. */
class SnippetOffer(val finalOffset: Int, val stops: List<Triple<Int, List<Pair<Int, Int>>, List<String>>>)

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
        cachesByPath[parsed.file.path]?.let { if (it.parsed === parsed && it.stamp == stamp) return it.caches }
        return KotlinResolverCaches().also { cachesByPath[parsed.file.path] = CachesEntry(parsed, stamp, it) }
    }

    private class CachesEntry(val parsed: KotlinParsedFile, val stamp: Long, val caches: KotlinResolverCaches)

    private fun resolverFor(parsed: KotlinParsedFile): KotlinResolver =
        KotlinResolver(parsed.ktFile, parsed, service, cachesFor(parsed))

    private val highlighter by lazy {
        KotlinSemanticHighlighter(
            parsedFor = { f -> parsedByPath[f.path] },
            resolverFor = ::resolverFor,
            refresh = { }, // the host re-asks per keystroke; nothing subscribes to an invalidation
            externalStampFor = { service.externalContentStamp(it) },
        )
    }

    private val inlays by lazy {
        KotlinInlayHintService(parsedFor = { f -> parsedByPath[f.path] }, resolverFor = ::resolverFor)
    }
    private val analysis = IncrementalSemanticAnalysis(service, resolveReady = { index != null })

    private var version = 0L

    /** The last parse of each file (reused while its text is unchanged), and the resolver caches built over it. */
    private val parsedByPath = HashMap<String, KotlinParsedFile>()
    private val cachesByPath = HashMap<String, CachesEntry>()

    /**
     * The project as the worker knows it: every file's CURRENT text, for open and unopened files alike. It is the
     * symbol model's overlay, so a declaration typed in one file is visible from the next without a save.
     */
    private val files = LinkedHashMap<String, String>()

    /** Make [path] part of the project with [text] (a new file, or a changed one the host is not editing right now). */
    fun put(path: String, text: String) {
        files[path] = text
        // The service lists a source root through the file system, so the file must exist there; the overlay then
        // supplies its text, which is always at least as new as what is written.
        writeFileAtomically(path, text.encodeToByteArray())
        service.setOverlay(HashMap(files))
    }

    /** Remove [path] from the project. */
    fun remove(path: String) {
        files.remove(path)
        parsedByPath.remove(path)
        cachesByPath.remove(path)
        deleteFile(path)
        service.setOverlay(HashMap(files))
    }

    /** Every file the project holds, in path order. */
    fun paths(): List<String> = files.keys.sorted()

    /** The text of [path] as the project holds it, or null. */
    fun textOf(path: String): String? = files[path]

    private class Buffer(override val file: VirtualFile, private val content: String) : DocumentSnapshot {
        override val version: Long = 1
        override val text: CharSequence get() = content
        override fun length(): Int = content.length
    }

    /** The parse of [path]'s [text], reused while the text is unchanged (the passes of one keystroke share it). */
    private fun parsed(path: String, text: String): KotlinParsedFile {
        parsedByPath[path]?.let { if (it.ktFile.text.contentEquals(text)) return it }
        val kt = KotlinParserHost.parse(path.substringAfterLast('/'), text)
        return KotlinParsedFile(kt, MemoryFile(path), ++version).also { parsedByPath[path] = it }
    }

    /** Make [text] the current content of [path], and the symbol model reflect every file; returns [path]'s parse. */
    private fun sync(path: String, text: String): KotlinParsedFile {
        if (files[path] != text) {
            files[path] = text
            if (fileInfo(path) == null) writeFileAtomically(path, text.encodeToByteArray()) // it must be listable
            service.setOverlay(HashMap(files))
        }
        val p = parsed(path, text)
        service.syncFocal(p.file.path, p.ktFile.text.hashCode()) { SourceIndexBuilder.extractFrom(p.ktFile, p, p.file.path) }
        return p
    }

    suspend fun complete(path: String, text: String, offset: Int): CompletionOutcome {
        val started = TimeSource.Monotonic.markNow()
        sync(path, text)
        val result = completion.complete(
            CompletionRequest(Buffer(MemoryFile(path), text), offset, CompletionTrigger.Explicit),
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
                    caretOffset = when (val c = it.caret) {
                        is dev.ide.lang.completion.CaretAction.At -> c.offset
                        is dev.ide.lang.completion.CaretAction.Select -> c.offset
                        else -> -1
                    },
                    caretSelect = (it.caret as? dev.ide.lang.completion.CaretAction.Select)?.length ?: 0,
                    snippet = (it.caret as? dev.ide.lang.completion.CaretAction.ExpandSnippet)?.expansion?.let { x ->
                        SnippetOffer(x.finalCaretOffset, x.stops.map { st -> Triple(st.index, st.ranges.map { r -> r.start to r.end }, st.choices) })
                    },
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
            parsedFor = { path -> parsedByPath[path] },
            refreshOverlay = { service.setOverlay(HashMap(files)) },
            syncFocal = { p -> service.syncFocal(p.file.path, p.ktFile.text.hashCode()) { SourceIndexBuilder.extractFrom(p.ktFile, p, p.file.path) } },
        )
    }
    private val signatures by lazy { KotlinSignatureHelpService(service) }
    private val formatter = KotlinFormatter()
    private val importOrganizer = KotlinImportOrganizer()

    private fun editsJson(edits: List<DocumentEdit>?): String = buildString {
        append('[')
        edits.orEmpty().forEachIndexed { i, e ->
            if (i > 0) append(',')
            append('[').append(e.offset).append(',').append(e.offset + e.oldLength).append(',').append(jsonString(e.newText.toString())).append(']')
        }
        append(']')
    }

    /** The code actions at [offset], as the iOS host lists them: import fixes, plus the two diagnostic-keyed ones. */
    private fun codeActions(file: MemoryFile, parsedFile: KotlinParsedFile, offset: Int): List<KotlinImportFix> {
        val out = ArrayList<KotlinImportFix>(features.importFixesAt(file, offset))
        val covering = analysis.diagnostics(parsedFile)
            .filter { offset >= it.range.start && offset <= it.range.end }
            .map { it.code }
            .toSet()
        if (covering.any { it in KotlinDiagnosticCodes.IMPLEMENT_MEMBERS }) features.implementMembersFix(file, offset)?.let { out += it }
        if (KotlinDiagnosticCodes.SUSPEND_OVERRIDE in covering) features.suspendModifierFix(file, offset)?.let { out += it }
        return out
    }

    /** What Find Usages and Rename act on at [offset], with every range of it in this file (declarations marked). */
    private fun usageTarget(file: MemoryFile, text: String, offset: Int, forRename: Boolean): KotlinUsageTarget? =
        features.usageTargetAt(file, text, offset, readSource = { null }, includeOverloads = forRename)

    private fun usageRanges(file: MemoryFile, text: String, target: KotlinUsageTarget): List<Triple<Int, Int, Boolean>> {
        val declarations = target.declarations.filter { it.first == file.path }.map { Triple(it.second.start, it.second.end, true) }
        val references = features.usagesIn(file, text, target).map { Triple(it.start, it.end, false) }
        return (declarations + references).distinctBy { it.first }.sortedBy { it.first }
    }

    /**
     * One entry point for the features that return a small answer: [method] names it, [start]/[end] are the caret
     * or the selection, and the result is JSON the host maps to its own types (`null` when there is nothing).
     */
    suspend fun call(method: String, path: String, text: String, start: Int, end: Int): String {
        val file = MemoryFile(path)
        val parsedFile = sync(path, text)
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
            // Go to declaration: a target in any file of the project can be opened (the host's navigation does it); a
            // library class has no source to show here, so it answers nothing.
            "definition" -> features.navigationTargets(file, text, start, NavKind.DECLARATION)
                .firstOrNull { it.file.path in files }?.let { "{\"path\":${jsonString(it.file.path)},\"offset\":${it.offset}}" } ?: "null"
            "actions" -> codeActions(file, parsedFile, start).joinToString(",", "[", "]") { jsonString(it.title) }
            "applyaction" -> editsJson(codeActions(file, parsedFile, start).getOrNull(end)?.edits)
            "usages" -> usageTarget(file, text, start, forRename = false)?.let { t ->
                usageRanges(file, text, t).joinToString(",", "[", "]") { "[${it.first},${it.second},${it.third}]" }
            } ?: "null"
            "prepare" -> usageTarget(file, text, start, forRename = true)?.takeIf { it.renameable }
                ?.let { "{\"oldName\":${jsonString(it.name)},\"kind\":${jsonString(it.kind)}}" } ?: "null"
            "expand" -> expandSelection(parsedFile, start, end, text.length)?.let { "[${it.start},${it.end}]" } ?: "null"
            else -> if (method.startsWith("rename:")) {
                // Every name range of the symbol under the caret, replaced by the new name (one file: nothing else to touch).
                val newName = method.removePrefix("rename:")
                usageTarget(file, text, start, forRename = true)?.takeIf { it.renameable }?.let { t ->
                    usageRanges(file, text, t).joinToString(",", "[", "]") { "[${it.first},${it.second},${jsonString(newName)}]" }
                } ?: "null"
            } else "null"
        }
    }

    suspend fun semanticTokens(path: String, text: String): List<TokenOutcome> {
        val p = sync(path, text)
        return highlighter.highlight(p.file).map {
            TokenOutcome(it.range.start, it.range.end, it.kind.id, it.modifiers.map(HighlightModifier::name))
        }
    }

    suspend fun inlayHints(path: String, text: String, start: Int, end: Int): List<HintOutcome> {
        val p = sync(path, text)
        return inlays.hints(p.file, TextRange(start, end)).map {
            HintOutcome(it.offset, it.parts.map { part -> part.text }, it.kind.name, it.tooltip, it.paddingLeft, it.paddingRight)
        }
    }

    fun diagnose(path: String, text: String): List<DiagnosticOutcome> {
        val p = sync(path, text)
        // The parser's own syntax errors, then the semantic checks over the tree.
        return (p.diagnostics + analysis.diagnostics(p)).map {
            DiagnosticOutcome(it.range.start, it.range.end, it.severity.name, it.message, it.code)
        }
    }

    companion object {
        /** Kotlin official: 4-space indent, 8-space continuation (what `CodeStyleSettings.default("kotlin")` is). */
        private val FORMAT_STYLE = FormatStyle(styleId = "kotlin_official", indentSize = 4, continuationIndent = 8, tabWidth = 4)

        const val PROJECT_DIR = "/project"
    }
}
