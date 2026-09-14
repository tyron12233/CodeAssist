package dev.ide.build.nativemodel

/**
 * A minimal JSON writer for the native-model dump. buildSrc has no JSON dependency and the dump is written
 * by exactly one producer, so a writer that handles the shapes below (maps, lists, strings, numbers,
 * booleans) is all that is needed. Output is pretty-printed with a 2-space indent so the committed dump
 * diffs readably.
 */
internal object NativeModelJson {

    fun write(value: Any?): String = StringBuilder().also { writeValue(value, it, 0) }.append('\n').toString()

    private fun writeValue(v: Any?, sb: StringBuilder, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Map<*, *> -> writeObject(v, sb, indent)
            is Collection<*> -> writeArray(v, sb, indent)
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeObject(map: Map<*, *>, sb: StringBuilder, indent: Int) {
        if (map.isEmpty()) return run { sb.append("{}") }
        sb.append("{\n")
        val inner = indent + 1
        map.entries.forEachIndexed { i, (k, value) ->
            pad(sb, inner)
            writeString(k.toString(), sb)
            sb.append(": ")
            writeValue(value, sb, inner)
            if (i < map.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent)
        sb.append('}')
    }

    private fun writeArray(list: Collection<*>, sb: StringBuilder, indent: Int) {
        if (list.isEmpty()) return run { sb.append("[]") }
        sb.append("[\n")
        val inner = indent + 1
        list.forEachIndexed { i, v ->
            pad(sb, inner)
            writeValue(v, sb, inner)
            if (i < list.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent)
        sb.append(']')
    }

    private fun pad(sb: StringBuilder, indent: Int) = repeat(indent) { sb.append("  ") }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
