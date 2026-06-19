package dev.ide.analytics.impl

import dev.ide.analytics.AnalyticsEvent
import dev.ide.analytics.EventCategory

/**
 * The on-disk line format for the durable event buffer — one event per line so the buffer survives an app
 * kill / offline period. Self-contained and round-trips byte-exactly; we control both ends, so rather than
 * pull in a JSON parser this uses a tab-separated, percent-escaped encoding:
 *
 *   `category \t name \t key=value \t key=value …`
 *
 * Every field is escaped for `%`, tab, and newline; prop keys additionally escape `=`. Unknown/garbled
 * lines decode to null and are dropped (a corrupt buffer must never crash startup).
 */
internal object EventQueueCodec {

    fun encode(e: AnalyticsEvent): String {
        val sb = StringBuilder()
        sb.append(esc(e.category.name)).append('\t').append(esc(e.name))
        for ((k, v) in e.props) sb.append('\t').append(escKey(k)).append('=').append(esc(v))
        return sb.toString()
    }

    fun decode(line: String): AnalyticsEvent? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val category = runCatching { EventCategory.valueOf(unesc(parts[0])) }.getOrNull() ?: return null
        val name = unesc(parts[1])
        val props = LinkedHashMap<String, String>()
        for (i in 2 until parts.size) {
            val eq = parts[i].indexOf('=')
            if (eq <= 0) continue
            props[unesc(parts[i].substring(0, eq))] = unesc(parts[i].substring(eq + 1))
        }
        return AnalyticsEvent(name, category, props)
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '%' -> append("%25"); '\t' -> append("%09"); '\n' -> append("%0A"); '\r' -> append("%0D")
            else -> append(c)
        }
    }

    // Prop keys must not contain the `=` separator either.
    private fun escKey(s: String): String = esc(s).replace("=", "%3D")

    private fun unesc(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { append(hex.toChar()); i += 3; continue }
            }
            append(c); i++
        }
    }
}
