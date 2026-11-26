// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import dev.ide.platform.JsonReader
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
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
