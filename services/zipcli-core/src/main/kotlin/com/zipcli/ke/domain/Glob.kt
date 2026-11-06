package com.zipcli.ke.domain

import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.CancellationException

/**
 * Wildcards: `*` (any run inside one path segment), `?` (one character), and a `**` segment
 * meaning "any number of directories". Hidden entries (".foo") only match when the pattern
 * segment itself starts with a dot.
 */
object Glob {

    fun hasWildcard(s: String): Boolean = s.any { it == '*' || it == '?' }

    fun toRegex(glob: String, ignoreCase: Boolean = false): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return if (ignoreCase) Regex(sb.toString(), RegexOption.IGNORE_CASE) else Regex(sb.toString())
    }

    private class Cand(val display: String, val real: String)

    private fun join(display: String, name: String) = when {
        display.isEmpty() -> name
        display.endsWith("/") -> display + name
        else -> "$display/$name"
    }

    private suspend fun safeList(fs: TerminalFileSystem, path: String): List<FileEntry> =
        try { fs.list(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    /**
     * Expands [pattern] against the filesystem. The result keeps the style of the pattern
     * (relative stays relative) and is naturally sorted. Empty when nothing matches.
     */
    suspend fun expand(fs: TerminalFileSystem, cwd: String, pattern: String): List<String> {
        val absolute = pattern.startsWith("/")
        val segs = pattern.split('/').filter { it.isNotEmpty() && it != "." }
        var cands = listOf(Cand(if (absolute) "/" else "", if (absolute) "/" else cwd))

        for ((idx, seg) in segs.withIndex()) {
            val last = idx == segs.lastIndex
            val next = ArrayList<Cand>()
            when {
                seg == "**" -> for (c in cands) {
                    next += c
                    val base = c.real.trimEnd('/')
                    val all = try { fs.walk(c.real) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    for (e in all) {
                        if (e.isDirectory && e.path != c.real) {
                            next += Cand(join(c.display, e.path.removePrefix(base).trimStart('/')), e.path)
                        }
                    }
                }
                hasWildcard(seg) -> {
                    val re = toRegex(seg)
                    for (c in cands) {
                        for (e in safeList(fs, c.real)) {
                            if (!re.matches(e.name)) continue
                            if (e.name.startsWith(".") && !seg.startsWith(".")) continue
                            if (last || e.isDirectory) next += Cand(join(c.display, e.name), e.path)
                        }
                    }
                }
                else -> for (c in cands) next += Cand(join(c.display, seg), VirtualPath.resolve(c.real, seg))
            }
            cands = next.distinctBy { it.real }
            if (cands.isEmpty()) return emptyList()
        }
        return cands.filter { fs.exists(it.real) }.map { it.display }.sortedWith(NaturalOrder)
    }
}
