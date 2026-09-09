package dev.ide.preview.impl

import dev.ide.preview.PreviewViewNode
import dev.ide.preview.PreviewViewProperty

/**
 * A tiny, dependency-free text codec for a captured [PreviewViewNode] tree — used by the `:preview` real-view
 * daemon to hand the hierarchy back to the UI process alongside the rendered pixels (written to a sidecar file
 * next to the pixel handoff, following the "control over IPC, bulk on the shared filesystem" convention). Kept
 * pure-Kotlin (no `org.json`/android) so it round-trips on the plain JVM and is unit-testable.
 *
 * Format: one line per node header (`class \t id \t l \t t \t r \t b \t propCount \t childCount`), then
 * `propCount` property lines (`group \t name \t value`), then the node's children serialized recursively in
 * pre-order. Every string field is escaped so a tab/newline in an attribute value can't corrupt the framing.
 */
object PreviewViewTreeCodec {

    fun encode(root: PreviewViewNode): String {
        val sb = StringBuilder()
        write(root, sb)
        return sb.toString()
    }

    /** Parse an [encode]d tree; null if [text] is blank or malformed (the caller then simply omits the tree). */
    fun decode(text: String): PreviewViewNode? {
        if (text.isBlank()) return null
        val lines = text.split('\n')
        return runCatching { readNode(lines, intArrayOf(0)) }.getOrNull()
    }

    private fun write(n: PreviewViewNode, sb: StringBuilder) {
        sb.append(esc(n.className)).append('\t')
            .append(esc(n.id ?: "")).append('\t')
            .append(n.left).append('\t').append(n.top).append('\t').append(n.right).append('\t').append(n.bottom).append('\t')
            .append(n.properties.size).append('\t').append(n.children.size).append('\n')
        for (p in n.properties) {
            sb.append(esc(p.group)).append('\t').append(esc(p.name)).append('\t').append(esc(p.value)).append('\n')
        }
        for (c in n.children) write(c, sb)
    }

    private fun readNode(lines: List<String>, cursor: IntArray): PreviewViewNode {
        val h = lines[cursor[0]++].split('\t')
        val className = unesc(h[0])
        val id = unesc(h[1]).ifEmpty { null }
        val left = h[2].toInt(); val top = h[3].toInt(); val right = h[4].toInt(); val bottom = h[5].toInt()
        val propCount = h[6].toInt(); val childCount = h[7].toInt()
        val props = ArrayList<PreviewViewProperty>(propCount)
        repeat(propCount) {
            val p = lines[cursor[0]++].split('\t')
            props.add(PreviewViewProperty(unesc(p[0]), unesc(p[1]), unesc(p.getOrElse(2) { "" })))
        }
        val children = ArrayList<PreviewViewNode>(childCount)
        repeat(childCount) { children.add(readNode(lines, cursor)) }
        return PreviewViewNode(className, id, left, top, right, bottom, props, children)
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        return buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> append('\\'); 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c); i++
                }
            }
        }
    }
}
