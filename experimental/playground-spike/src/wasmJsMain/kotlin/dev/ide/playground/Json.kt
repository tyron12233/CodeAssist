// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground

// The few shapes the page needs, written by hand: a message payload is one string, so a result crosses as JSON,
// and a general JSON library would be more machinery than three small objects.

internal fun jsonString(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}

internal fun jsonOrNull(s: String?): String = if (s == null) "null" else jsonString(s)

internal fun completionsJson(r: CompletionOutcome): String = buildString {
    append("{\"from\":").append(r.from).append(",\"to\":").append(r.to)
    append(",\"incomplete\":").append(r.incomplete).append(",\"millis\":").append(r.millis).append(",\"items\":[")
    r.items.forEachIndexed { i, o ->
        if (i > 0) append(',')
        append("{\"label\":").append(jsonString(o.label))
        append(",\"insert\":").append(jsonString(o.insertText))
        append(",\"kind\":").append(jsonString(o.kind))
        append(",\"detail\":").append(jsonOrNull(o.detail))
        append(",\"container\":").append(jsonOrNull(o.container))
        append(",\"edits\":[")
        o.additionalEdits.forEachIndexed { j, (from, to, text) ->
            if (j > 0) append(',')
            append("[").append(from).append(',').append(to).append(',').append(jsonString(text)).append(']')
        }
        append("]")
        if (o.caretOffset >= 0) append(",\"caret\":[").append(o.caretOffset).append(',').append(o.caretSelect).append(']')
        o.snippet?.let { sn ->
            append(",\"snippet\":{\"final\":").append(sn.finalOffset).append(",\"stops\":[")
            sn.stops.forEachIndexed { k, (index, ranges, choices) ->
                if (k > 0) append(',')
                append('[').append(index).append(",[")
                ranges.forEachIndexed { m, (s, e) -> if (m > 0) append(','); append('[').append(s).append(',').append(e).append(']') }
                append("],[")
                choices.forEachIndexed { m, c -> if (m > 0) append(','); append(jsonString(c)) }
                append("]]")
            }
            append("]}")
        }
        append('}')
    }
    append("]}")
}

internal fun diagnosticsJson(list: List<DiagnosticOutcome>): String = buildString {
    append('[')
    list.forEachIndexed { i, d ->
        if (i > 0) append(',')
        append("{\"from\":").append(d.from).append(",\"to\":").append(d.to)
        append(",\"severity\":").append(jsonString(d.severity))
        append(",\"message\":").append(jsonString(d.message))
        append(",\"code\":").append(jsonOrNull(d.code)).append('}')
    }
    append(']')
}

// `[from, to, kind, "MODIFIER,MODIFIER"]` per token: a file has thousands, so the compact shape matters.
internal fun tokensJson(list: List<TokenOutcome>): String = buildString {
    append('[')
    list.forEachIndexed { i, t ->
        if (i > 0) append(',')
        append('[').append(t.from).append(',').append(t.to).append(',').append(jsonString(t.kind))
        append(',').append(jsonString(t.modifiers.joinToString(","))).append(']')
    }
    append(']')
}

internal fun hintsJson(list: List<HintOutcome>): String = buildString {
    append('[')
    list.forEachIndexed { i, h ->
        if (i > 0) append(',')
        append("{\"offset\":").append(h.offset).append(",\"kind\":").append(jsonString(h.kind))
        append(",\"parts\":[").append(h.parts.joinToString(",") { jsonString(it) }).append(']')
        append(",\"tooltip\":").append(jsonOrNull(h.tooltip))
        append(",\"left\":").append(h.paddingLeft).append(",\"right\":").append(h.paddingRight).append('}')
    }
    append(']')
}
