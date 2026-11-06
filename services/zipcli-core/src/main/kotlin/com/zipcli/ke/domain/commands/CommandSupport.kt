package com.zipcli.ke.domain.commands

import com.zipcli.ke.domain.Glob
import com.zipcli.ke.domain.NaturalOrder
import com.zipcli.ke.domain.Suggest
import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Published limits (shown by `info`, used by the MCP layer). */
object Limits {
    const val APP_VERSION = "1.1.0"
    const val CAT_LINES = 500
    const val CAT_LINES_MAX = 50_000
    const val FIND_MATCHES = 200
    const val FIND_MATCHES_MAX = 10_000
    const val SEARCH_RESULTS = 500
    const val MCP_OUTPUT_CHARS = 100_000
    const val MCP_COMMAND_CHARS = 64 * 1024
    const val SCRIPT_COMMANDS = 20_000
}

internal const val IO_BUFFER = 64 * 1024

/** Files bigger than this are skipped by find/replace/cat instead of being loaded into memory. */
internal const val TEXT_SIZE_LIMIT = 8L * 1024 * 1024

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1fK", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1fG", bytes / (1024.0 * 1024 * 1024))
}

internal fun formatDate(millis: Long): String =
    if (millis <= 0) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

/** Mount roots (/home, /tree, /tree/<id>, ...) must never be deleted or moved away. */
internal fun isProtectedRoot(path: String): Boolean {
    val parts = VirtualPath.normalize(path).split('/').filter { it.isNotEmpty() }
    return parts.size <= 1 || (parts[0] == "tree" && parts.size == 2)
}

/** True when [child] is [parent] itself or lives inside it. */
internal fun isInside(parent: String, child: String): Boolean {
    val p = VirtualPath.normalize(parent)
    val c = VirtualPath.normalize(child)
    return c == p || p == "/" || c.startsWith("$p/")
}

/** Where `src` lands when copied/moved to [dst]: inside it if it is an existing directory (like `cp`). */
internal suspend fun resolveTarget(fs: TerminalFileSystem, src: String, dst: String): String {
    val e = fs.stat(dst)
    return if (e != null && e.isDirectory) VirtualPath.resolve(dst, VirtualPath.name(src)) else dst
}

/** Opens [path] as a UTF-8 line reader, or returns null when it looks binary (NUL byte in the first 8 KB). */
internal suspend fun openTextReader(fs: TerminalFileSystem, path: String): BufferedReader? {
    val input = BufferedInputStream(fs.openRead(path), IO_BUFFER)
    try {
        input.mark(8192)
        val head = ByteArray(8192)
        val n = input.read(head)
        if (n > 0 && (0 until n).any { head[it] == 0.toByte() }) { input.close(); return null }
        input.reset()
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8), IO_BUFFER)
    } catch (e: Throwable) {
        try { input.close() } catch (_: Exception) {}
        throw e
    }
}

internal sealed interface TextRead {
    data class Ok(val text: String) : TextRead
    object Binary : TextRead
    object TooLarge : TextRead
}

/**
 * Reads a whole file as text but refuses anything that is not valid UTF-8 text.
 * Used before rewriting a file, so binary files are never corrupted by a decode/encode round trip.
 */
internal suspend fun readTextStrict(fs: TerminalFileSystem, entry: FileEntry): TextRead {
    if (entry.size > TEXT_SIZE_LIMIT) return TextRead.TooLarge
    val bytes = fs.openRead(entry.path).use { input ->
        val out = ByteArrayOutputStream(if (entry.size in 1..TEXT_SIZE_LIMIT) entry.size.toInt() else 8192)
        val buf = ByteArray(IO_BUFFER)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > TEXT_SIZE_LIMIT) return TextRead.TooLarge
            out.write(buf, 0, r)
        }
        out.toByteArray()
    }
    if (bytes.any { it == 0.toByte() }) return TextRead.Binary
    return try {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        TextRead.Ok(dec.decode(ByteBuffer.wrap(bytes)).toString())
    } catch (e: CharacterCodingException) {
        TextRead.Binary
    }
}

/**
 * "Not found: /x/fiel.txt. Did you mean 'file.txt'?" — looks at the siblings of the missing path.
 * Without a close match it lists what the folder does contain (first few names), which is usually what the
 * caller needs next. [wildcardHint] explains a `*`/`?` in the path for commands that do not expand wildcards.
 */
internal suspend fun notFoundMessage(
    fs: TerminalFileSystem, path: String, dirsOnly: Boolean = false, wildcardHint: String? = null,
): String {
    val base = "Not found: $path"
    val wild = if (wildcardHint != null && Glob.hasWildcard(VirtualPath.name(path))) " $wildcardHint" else ""
    return try {
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = VirtualPath.name(path)
        val names = fs.list(parent).filter { !dirsOnly || it.isDirectory }.map { it.name }
        val hint = Suggest.closest(name, names)
        when {
            hint != null -> "$base. Did you mean '$hint'?$wild"
            names.isEmpty() -> "$base ($parent is empty).$wild"
            else -> {
                val shown = names.sortedWith(NaturalOrder).take(8).joinToString(", ")
                "$base. $parent contains: $shown" + (if (names.size > 8) ", … (${names.size - 8} more)" else "") + ".$wild"
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        base + wild
    }
}

/** Minimal JSON writer for `--json` output (the domain layer has no serialization dependency). */
internal object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> str(k) + ":" + value(v) }

    fun arr(items: List<Any?>): String = items.joinToString(",", "[", "]") { value(it) }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean, is Int, is Long -> v.toString()
        is Raw -> v.json
        is List<*> -> arr(v)
        else -> str(v.toString())
    }

    /** Already-encoded JSON, embedded as is. */
    class Raw(val json: String)
}

/** Counts of what a directory holds, for the delete confirmation. */
internal data class TreeStats(val files: Int, val dirs: Int, val bytes: Long)

internal suspend fun treeStats(fs: TerminalFileSystem, root: String): TreeStats {
    var files = 0; var dirs = 0; var bytes = 0L
    for (e in fs.walk(root)) {
        if (e.path == root) continue
        if (e.isDirectory) dirs++ else { files++; bytes += e.size }
    }
    return TreeStats(files, dirs, bytes)
}

/** Creates the parent directory of [path] when missing (SAF cannot do this implicitly). */
internal suspend fun ensureParent(fs: TerminalFileSystem, path: String) {
    val parent = VirtualPath.normalize(path).substringBeforeLast('/', "")
    if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
}
