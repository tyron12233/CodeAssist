// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import dev.ide.platform.JsonReader
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionEdits
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiDefinition
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiRenameTarget
import dev.ide.ui.backend.UiUsageHighlight
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiSignatureParam
import dev.ide.ui.backend.UiTextRange
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiTextEdit

/**
 * The editor's backend for the Playground: everything a no-op [StubBackend] is, except that completion and
 * diagnostics are answered by the engine worker. The editor asks for them on every keystroke the way it does
 * against the real IDE; the answers are the same Kotlin completion and checks, run in the worker.
 */
class PlaygroundBackend(private val engine: EngineClient) : StubBackend() {
    private val documents = HashMap<String, String>()

    /** The text the editor shows now, and a way to edit it: a rename the editor did not apply itself needs both. */
    var currentText: () -> String = { "" }
    var applyEdits: (List<UiTextEdit>) -> Unit = {}

    override fun updateDocument(path: String, text: String) { documents[path] = text }
    override fun readFile(path: String): String = documents[path] ?: ""

    override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
        val empty = UiCompletionResult(emptyList(), offset, offset)
        val json = engine.complete(text, offset).ifEmpty { return empty }
        val root = JsonReader.parseOrNull(json) as? Map<*, *> ?: return empty
        val items = (root["items"] as? List<*>).orEmpty().mapIndexedNotNull { i, raw ->
            val it = raw as? Map<*, *> ?: return@mapIndexedNotNull null
            UiCompletionItem(
                label = it["label"] as? String ?: return@mapIndexedNotNull null,
                insertText = it["insert"] as? String ?: "",
                detail = it["detail"] as? String,
                container = it["container"] as? String,
                kind = completionKind(it["kind"] as? String),
                sortPriority = i, // the engine's order IS its ranking
                additionalEdits = (it["edits"] as? List<*>).orEmpty().mapNotNull { e ->
                    val t = e as? List<*> ?: return@mapNotNull null
                    UiTextEdit((t[0] as Number).toInt(), (t[1] as Number).toInt(), t[2] as String)
                },
            )
        }
        return UiCompletionResult(
            items = items,
            replaceStart = (root["from"] as? Number)?.toInt() ?: offset,
            replaceEnd = (root["to"] as? Number)?.toInt() ?: offset,
            // The engine filtered and ranked for the token as typed: ask again as it grows rather than narrowing here.
            mayFilterLocally = false,
        )
    }

    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> {
        val json = engine.diagnose(text).ifEmpty { return emptyList() }
        val list = JsonReader.parseOrNull(json) as? List<*> ?: return emptyList()
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        return list.mapNotNull { raw ->
            val d = raw as? Map<*, *> ?: return@mapNotNull null
            val start = (d["from"] as? Number)?.toInt() ?: return@mapNotNull null
            val end = (d["to"] as? Number)?.toInt() ?: start
            val line = lineStarts.indexOfLast { it <= start }.coerceAtLeast(0)
            UiDiagnostic(
                severity = when (d["severity"]) {
                    "ERROR" -> UiSeverity.Error
                    "WARNING" -> UiSeverity.Warning
                    "INFO" -> UiSeverity.Info
                    else -> UiSeverity.Hint
                },
                line = line + 1,
                col = start - lineStarts[line] + 1,
                message = d["message"] as? String ?: "",
                startOffset = start,
                endOffset = end,
            )
        }
    }

    override suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> {
        val json = engine.semantic(text).ifEmpty { return emptyList() }
        val list = JsonReader.parseOrNull(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val t = raw as? List<*> ?: return@mapNotNull null
            UiSemanticToken(
                startOffset = (t[0] as Number).toInt(),
                endOffset = (t[1] as Number).toInt(),
                kind = t[2] as String,
                modifiers = (t[3] as String).split(',').mapNotNullTo(LinkedHashSet()) { modifier(it) },
            )
        }
    }

    override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> {
        val json = engine.hints(text, startOffset, endOffset).ifEmpty { return emptyList() }
        val list = JsonReader.parseOrNull(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val h = raw as? Map<*, *> ?: return@mapNotNull null
            UiInlayHint(
                offset = (h["offset"] as? Number)?.toInt() ?: return@mapNotNull null,
                parts = (h["parts"] as? List<*>).orEmpty().map { UiInlayPart(it as String) },
                kind = when (h["kind"]) {
                    "TYPE" -> UiInlayKind.Type
                    "PARAMETER" -> UiInlayKind.Parameter
                    "CHAINING" -> UiInlayKind.Chaining
                    else -> UiInlayKind.Other
                },
                tooltip = h["tooltip"] as? String,
                paddingLeft = h["left"] == true,
                paddingRight = h["right"] == true,
            )
        }
    }

    private fun num(v: Any?): Int = (v as Number).toInt()

    private suspend fun json(method: String, text: String, start: Int = 0, end: Int = 0): Any? =
        engine.call(method, text, start, end).takeIf { it.isNotEmpty() }?.let { JsonReader.parseOrNull(it) }

    override suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> =
        (json("folds", text) as? List<*>).orEmpty().mapNotNull { raw ->
            val f = raw as? List<*> ?: return@mapNotNull null
            UiFoldRegion(num(f[0]), num(f[1]), f[2] as String, f[3] as String, f[4] == true)
        }

    override suspend fun signatureHelp(path: String, text: String, offset: Int): UiSignatureHelp? {
        val h = json("signature", text, offset) as? Map<*, *> ?: return null
        return UiSignatureHelp(
            signatures = (h["signatures"] as? List<*>).orEmpty().mapNotNull { raw ->
                val sig = raw as? Map<*, *> ?: return@mapNotNull null
                UiSignature(
                    label = sig["label"] as String,
                    parameters = (sig["params"] as? List<*>).orEmpty().mapNotNull { p ->
                        val a = p as? List<*> ?: return@mapNotNull null
                        UiSignatureParam(a[0] as String, num(a[1]), num(a[2]), a[3] == true)
                    },
                    documentation = sig["doc"] as? String,
                    activeParameter = (sig["active"] as? Number)?.toInt()?.takeIf { it >= 0 },
                )
            },
            activeSignature = num(h["activeSignature"]),
            activeParameter = num(h["activeParameter"]),
        )
    }

    override suspend fun quickDocAt(path: String, text: String, offset: Int): UiQuickDoc? {
        val d = json("quickdoc", text, offset) as? Map<*, *> ?: return null
        return UiQuickDoc(
            signature = d["signature"] as String, name = d["name"] as String, kind = d["kind"] as String,
            container = d["container"] as? String, doc = d["doc"] as? String, docFormat = d["docFormat"] as String,
        )
    }

    private fun edits(v: Any?): List<UiTextEdit> = (v as? List<*>).orEmpty().mapNotNull { raw ->
        val e = raw as? List<*> ?: return@mapNotNull null
        UiTextEdit(num(e[0]), num(e[1]), e[2] as String)
    }

    override suspend fun formatDocument(path: String, text: String): List<UiTextEdit> = edits(json("format", text, -1, -1))

    override suspend fun formatRange(path: String, text: String, selStart: Int, selEnd: Int): List<UiTextEdit> =
        edits(json("format", text, selStart, selEnd))

    override suspend fun optimizeImports(path: String, text: String): List<UiTextEdit> = edits(json("imports", text))

    override suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? =
        (json("definition", text, offset) as? Map<*, *>)?.let { UiDefinition(path, num(it["offset"])) }

    override suspend fun expandSelection(path: String, text: String, selStart: Int, selEnd: Int): UiTextRange? =
        (json("expand", text, selStart, selEnd) as? List<*>)?.let { UiTextRange(num(it[0]), num(it[1])) }

    override suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> =
        (json("actions", text, selStart) as? List<*>).orEmpty().mapIndexedNotNull { i, title ->
            (title as? String)?.let { UiAction(i, it, UiActionKind.QUICK_FIX) }
        }

    // The worker recomputes the list for this text and caret and takes the n-th: a fix computed against older text
    // lands in the wrong place, so nothing is remembered between listing and applying.
    override suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): UiActionEdits =
        UiActionEdits(focal = edits(json("applyaction", text, selStart, actionId)))

    override suspend fun usageHighlights(path: String, text: String, offset: Int): List<UiUsageHighlight>? =
        (json("usages", text, offset) as? List<*>)?.mapNotNull { raw ->
            val u = raw as? List<*> ?: return@mapNotNull null
            UiUsageHighlight(num(u[0]), num(u[1]), write = u[2] == true)
        }

    override suspend fun prepareRename(path: String, text: String, offset: Int): UiRenameTarget? =
        (json("prepare", text, offset) as? Map<*, *>)?.let { UiRenameTarget(it["oldName"] as String, it["kind"] as String) }

    /**
     * Rename the symbol at [offset] to [newName]. The editor's in-place rename has already rewritten this file and
     * passes the text from BEFORE it, to have "the rest of the project" renamed: here there is none, so that call
     * only confirms. The dialog form passes the text as it is, and then the edits are ours to apply.
     */
    override suspend fun rename(path: String, text: String, offset: Int, newName: String): UiRenameResult {
        val found = edits(engine.call("rename:$newName", text, offset, 0).takeIf { it.isNotEmpty() }?.let { JsonReader.parseOrNull(it) })
        if (found.isEmpty()) return UiRenameResult(false, "Nothing here can be renamed.")
        if (text == currentText()) applyEdits(found)
        return UiRenameResult(true, "", occurrences = found.size, filesChanged = 1)
    }

    private fun modifier(name: String): UiHighlightModifier? = when (name) {
        "DECLARATION" -> UiHighlightModifier.Declaration
        "STATIC" -> UiHighlightModifier.Static
        "ABSTRACT" -> UiHighlightModifier.Abstract
        "DEPRECATED" -> UiHighlightModifier.Deprecated
        "READONLY" -> UiHighlightModifier.Readonly
        "MUTABLE" -> UiHighlightModifier.Mutable
        "EXTENSION" -> UiHighlightModifier.Extension
        "COMPOSABLE" -> UiHighlightModifier.Composable
        "SUSPEND" -> UiHighlightModifier.Suspend
        else -> null
    }

    private fun completionKind(name: String?): UiCompletionKind = when (name) {
        "CLASS" -> UiCompletionKind.Class
        "INTERFACE" -> UiCompletionKind.Interface
        "ENUM" -> UiCompletionKind.Enum
        "ANNOTATION_TYPE" -> UiCompletionKind.AnnotationType
        "RECORD" -> UiCompletionKind.Record
        "METHOD" -> UiCompletionKind.Method
        "CONSTRUCTOR" -> UiCompletionKind.Constructor
        "FIELD" -> UiCompletionKind.Field
        "ENUM_CONSTANT" -> UiCompletionKind.EnumConstant
        "VARIABLE" -> UiCompletionKind.Variable
        "PARAMETER" -> UiCompletionKind.Parameter
        "TYPE_PARAMETER" -> UiCompletionKind.TypeParameter
        "PACKAGE" -> UiCompletionKind.Package
        "KEYWORD" -> UiCompletionKind.Keyword
        "SNIPPET" -> UiCompletionKind.Snippet
        else -> UiCompletionKind.Word
    }
}
