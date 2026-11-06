package com.zipcli.ke.domain.commands

import com.zipcli.ke.domain.CommandResult
import com.zipcli.ke.domain.CommandSpec
import com.zipcli.ke.domain.ErrorCodes
import com.zipcli.ke.domain.FlagSpec
import com.zipcli.ke.domain.Glob
import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

fun buildContentCommands(): List<CommandSpec> = listOf(
    editSpec,
    updateSpec,
    catSpec,
    findSpec,
    replaceSpec,
    clearSpec,
    historySpec,
)

private fun describeSkip(r: TextRead, path: String): String = when (r) {
    TextRead.Binary -> "$path looks like a binary / non-UTF-8 file; refusing to treat it as text"
    TextRead.TooLarge -> "$path is larger than ${formatSize(TEXT_SIZE_LIMIT)}"
    is TextRead.Ok -> ""
}

// ───────────────────────────── edit ─────────────────────────────

private val editSpec = CommandSpec(
    name = "edit",
    summary = "Open a file in the editor sheet",
    usage = "edit <path>",
    minArgs = 1,
    maxArgs = 1,
    execute = execute@ { ctx ->
        val path = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        val entry = ctx.fs.stat(path)
            ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, path))
        if (entry.isDirectory) {
            return@execute CommandResult.Failure("Not a file: $path")
        }
        when (val r = readTextStrict(ctx.fs, entry)) {
            is TextRead.Ok -> CommandResult.OpenEditor(path, r.text)
            else -> CommandResult.Failure(describeSkip(r, path))
        }
    },
)

// ───────────────────────────── update ─────────────────────────────

private val updateSpec = CommandSpec(
    name = "update",
    summary = "Overwrite (or --append to) a file; creates it if missing",
    usage = "update <path> [--content <text> | <<'EOF' heredoc] [--append]",
    minArgs = 1,
    maxArgs = 1,
    flags = listOf(
        FlagSpec("content", 'c', true, "new content (otherwise opens the editor; single-quote to keep it literal)"),
        FlagSpec("append", 'a', false, "add the content to the end instead of replacing the file"),
    ),
    execute = execute@ { ctx ->
        val path = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        val entry = ctx.fs.stat(path)
        if (entry?.isDirectory == true) {
            return@execute CommandResult.Failure("Not a file: $path")
        }

        val inline = ctx.parsed.flagValue("content")
        val append = ctx.parsed.hasFlag("append")
        if (append && inline == null) {
            return@execute CommandResult.Failure("--append needs --content <text>")
        }
        if (inline != null) {
            ensureParent(ctx.fs, path)
            if (append) {
                ctx.fs.openWrite(path, append = true).use { it.write(inline.toByteArray(Charsets.UTF_8)) }
                return@execute CommandResult.Success(listOf("Appended to $path"))
            }
            ctx.fs.writeText(path, inline)
            return@execute CommandResult.Success(listOf("Updated $path"))
        }

        val existing = if (entry != null) {
            when (val r = readTextStrict(ctx.fs, entry)) {
                is TextRead.Ok -> r.text
                else -> return@execute CommandResult.Failure(describeSkip(r, path))
            }
        } else ""
        CommandResult.OpenEditor(path, existing)
    },
)

// ───────────────────────────── cat ─────────────────────────────

private val catSpec = CommandSpec(
    name = "cat",
    summary = "Print file contents",
    usage = "cat <path...> [-n] [--lines N] [--offset N] [--json]",
    minArgs = 1,
    expandGlobs = true,
    flags = listOf(
        FlagSpec("number", 'n', false, "number the lines"),
        FlagSpec("lines", null, true, "maximum lines to print (default 500)"),
        FlagSpec("offset", null, true, "skip this many lines first (page through a long file)"),
        FlagSpec("json", null, false, "machine-readable: one JSON array of {path, lines, total, truncated, next_offset}"),
    ),
    execute = execute@ { ctx ->
        val limit = ctx.parsed.flagValue("lines")?.toIntOrNull()?.coerceIn(1, 50_000) ?: 500
        val offset = ctx.parsed.flagValue("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val numbered = ctx.parsed.hasFlag("number")
        val json = ctx.parsed.hasFlag("json")
        val out = ArrayList<String>()
        val files = ArrayList<String>()
        var shown = 0
        var truncated = false
        var nextOffset = 0
        for (arg in ctx.parsed.args) {
            val path = VirtualPath.resolve(ctx.cwd, arg)
            val entry = ctx.fs.stat(path) ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, path))
            if (entry.isDirectory) return@execute CommandResult.Failure("$path is a directory")
            val text = when (val r = readTextStrict(ctx.fs, entry)) {
                is TextRead.Ok -> r.text
                else -> return@execute CommandResult.Failure(describeSkip(r, path))
            }
            // Text mode prints the empty "line" after a final newline as before; JSON reports real lines only.
            val all = text.lineSequence().toList().let { if (json && it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
            if (ctx.parsed.args.size > 1 && !json) out += "==> $path <=="
            val fileLines = ArrayList<String>()
            var n = offset
            var cut = false
            for (line in all.drop(offset)) {
                if (shown >= limit) { cut = true; truncated = true; nextOffset = n; break }
                n++; shown++
                if (json) fileLines += line
                else out += if (numbered) "%4d  %s".format(n, line) else line
            }
            if (json) files += Json.obj(
                "path" to path, "lines" to fileLines, "total" to all.size, "truncated" to cut,
                "next_offset" to (if (cut) nextOffset else null),
            )
            if (truncated) break
        }
        if (json) return@execute CommandResult.Success(listOf(Json.arr(files.map { Json.Raw(it) })))
        if (truncated) out += "[truncated at $limit lines — use --lines to raise, or --offset $nextOffset to continue]"
        CommandResult.Success(out)
    },
)

// ───────────────────────────── find ─────────────────────────────

private fun snippet(s: String, max: Int = 160) = if (s.length > max) s.take(max) + "…" else s

private class Hit(val path: String, val line: Int, val text: String, val more: List<String> = emptyList())

private val findSpec = CommandSpec(
    name = "find",
    summary = "Find text inside files (grep), incl. multi-line blocks. Skips binary files",
    usage = "find <pattern> [path] [-r] [-i|-s] [-w] [-m] [--reindent] [--regex] [--name <glob>] [--max N] [--offset N] [--count-only] [--json]",
    minArgs = 1,
    maxArgs = 2,
    flags = listOf(
        FlagSpec("recursive", 'r', false, "search directories recursively"),
        FlagSpec("ignore-case", 'i', false, "ignore case (default: automatic when the pattern has no capitals)"),
        FlagSpec("case", 's', false, "force case-sensitive matching"),
        FlagSpec("word", 'w', false, "whole-word match"),
        FlagSpec("multiline", 'm', false, "match across lines (automatic when the pattern contains a line break)"),
        FlagSpec("reindent", null, false, "multi-line: ignore differences in indentation and trailing blanks"),
        FlagSpec("dotall", null, false, "regex: let . match line breaks"),
        FlagSpec("regex", null, false, "treat <pattern> as a regular expression (^ and $ match per line)"),
        FlagSpec("name", null, true, "only search files whose name matches this glob, e.g. *.kt (find does not expand wildcards in <path>)"),
        FlagSpec("max", null, true, "maximum matches to return (default 200)"),
        FlagSpec("offset", null, true, "skip the first N matches (continue a capped result)"),
        FlagSpec("count-only", null, false, "print only how many matches there are, not the matches"),
        FlagSpec("summary", null, false, "same as --count-only"),
        FlagSpec("json", null, false, "machine-readable output: {matches:[{path,line,text,more}], count, files, searched, skipped, truncated, next_offset}"),
    ),
    exitStatus = "0 = at least one match, 1 = no matches (grep-style, so 'find x dir || echo none' works), 2 = usage error, 3 = path not found",
    execute = execute@ { ctx ->
        val pattern = ctx.parsed.arg(0)!!
        val root = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(1) ?: ".")
        val recursive = ctx.parsed.hasFlag("recursive")
        val regexMode = ctx.parsed.hasFlag("regex")
        val reindent = ctx.parsed.hasFlag("reindent")
        val countOnly = ctx.parsed.hasFlag("count-only") || ctx.parsed.hasFlag("summary")
        val json = ctx.parsed.hasFlag("json")
        val maxHits = if (countOnly) Int.MAX_VALUE else ctx.parsed.flagValue("max")?.toIntOrNull()?.coerceIn(1, 10_000) ?: 200
        val offset = ctx.parsed.flagValue("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val nameFilter = ctx.parsed.flagValue("name")?.let { Glob.toRegex(it, ignoreCase = true) }

        // Everything that depends on the caller's pattern is built inside this try, so a bad pattern is always
        // reported as "Invalid pattern", never as an unexplained internal error.
        val matcher = try {
            val ignoreCase = when {
                ctx.parsed.hasFlag("case") -> false
                ctx.parsed.hasFlag("ignore-case") -> true
                else -> BlockMatcher.smartCaseIgnore(pattern, regexMode)   // "smart case"
            }
            BlockMatcher.build(pattern, regexMode, ignoreCase, ctx.parsed.hasFlag("word"), reindent, ctx.parsed.hasFlag("dotall"))
        } catch (e: java.util.regex.PatternSyntaxException) {
            return@execute CommandResult.Failure("Invalid pattern: ${e.description} (at position ${e.index} of '${e.pattern}')", ErrorCodes.USAGE)
        } catch (e: Exception) {
            return@execute CommandResult.Failure("Invalid pattern: ${e.message}", ErrorCodes.USAGE)
        }
        val ignoreCaseLiteral = when {
            ctx.parsed.hasFlag("case") -> false
            ctx.parsed.hasFlag("ignore-case") -> true
            else -> BlockMatcher.smartCaseIgnore(pattern, regexMode)
        }
        val plainWord = !regexMode && !ctx.parsed.hasFlag("word")
        // Whole-file scan when the pattern can cross lines: a line break in it, an escaped \n in a regex, (?s)…
        val whole = ctx.parsed.hasFlag("multiline") || reindent || pattern.contains('\n') || matcher.spansLines

        val rootEntry = ctx.fs.stat(root)
            ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, root,
                wildcardHint = "'find' does not expand wildcards in <path>: give a folder and use --name '<glob>' with -r."))
        val candidates: List<FileEntry> = when {
            !rootEntry.isDirectory -> listOf(rootEntry)
            recursive -> ctx.fs.walk(root).filter { !it.isDirectory }
            else -> ctx.fs.list(root).filter { !it.isDirectory }
        }.filter { nameFilter == null || nameFilter.matches(it.name) }

        val hits = ArrayList<Hit>(minOf(maxHits, 32))
        var matchCount = 0          // matches recorded (after --offset)
        var seen = 0                // matches met, including skipped ones
        var scanned = 0
        var skipped = 0
        val filesWithHits = HashSet<String>()
        var stoppedEarly = false

        for (entry in candidates) {
            if (matchCount >= maxHits) { stoppedEarly = true; break }
            coroutineContext.ensureActive()
            if (++scanned % 25 == 0) ctx.progress("find: $scanned/${candidates.size} files, $matchCount matches")
            if (entry.size > TEXT_SIZE_LIMIT) { skipped++; continue }

            if (whole) {
                // Whole-file matching: the pattern may span lines.
                val text = try {
                    when (val r = readTextStrict(ctx.fs, entry)) { is TextRead.Ok -> r.text; else -> null }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (text == null) { skipped++; continue }
                val counter = LineCounter(text)
                for (m in matcher.regex.findAll(text)) {
                    if (matchCount >= maxHits) { stoppedEarly = true; break }
                    if (seen++ < offset) continue
                    matchCount++
                    filesWithHits += entry.path
                    if (countOnly) continue
                    val lineNo = counter.lineAt(m.range.first)
                    val lines = m.value.split('\n').map { it.trimEnd('\r') }
                    // First line shown as it appears in the file (indentation kept): include text before the match on that line.
                    val ls = text.lastIndexOf('\n', m.range.first - 1) + 1
                    val first = text.substring(ls, m.range.first) + lines[0]
                    val more = ArrayList<String>()
                    lines.drop(1).take(4).forEach { more += snippet(it) }
                    if (lines.size > 5) more += "… ${lines.size - 5} more line(s)"
                    hits += Hit(entry.path, lineNo, snippet(first), more)
                }
                continue
            }

            val reader = try { openTextReader(ctx.fs, entry.path) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { skipped++; continue }
            if (reader == null) { skipped++; continue }

            try {
                var lineNo = 0
                while (matchCount < maxHits) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (lineNo % 4096 == 0) coroutineContext.ensureActive()
                    val matched = if (plainWord) line.contains(pattern, ignoreCaseLiteral) else matcher.regex.containsMatchIn(line)
                    if (matched) {
                        if (seen++ < offset) continue
                        matchCount++
                        filesWithHits += entry.path
                        if (!countOnly) hits += Hit(entry.path, lineNo, snippet(line))   // indentation is kept as in the file
                    }
                }
                if (matchCount >= maxHits) stoppedEarly = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                skipped++       // unreadable mid-way; keep what we have
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
        }

        val capped = !countOnly && stoppedEarly && matchCount >= maxHits
        val stats = "searched $scanned file(s)" + if (skipped > 0) ", skipped $skipped binary/large/unreadable" else ""
        val status = if (matchCount == 0) 1 else 0

        if (json) {
            val body = Json.obj(
                "matches" to hits.map { h -> Json.Raw(Json.obj("path" to h.path, "line" to h.line, "text" to h.text, "more" to h.more)) },
                "count" to matchCount, "files" to filesWithHits.size, "searched" to scanned, "skipped" to skipped,
                "truncated" to capped, "next_offset" to (if (capped) offset + matchCount else null),
            )
            return@execute CommandResult.Success(listOf(body), status = status)
        }
        if (matchCount == 0) {
            // grep-style: no match is exit status 1, so 'find TODO dir || echo none' works in scripts.
            CommandResult.Success(
                listOf("No matches ($stats)." + if (!recursive && rootEntry.isDirectory) " Try -r to include subfolders." else ""),
                status = 1,
            )
        } else {
            val out = ArrayList<String>()
            for (h in hits) {
                out += "${h.path}:${h.line}: ${h.text}"
                h.more.forEach { out += "    │ $it" }
            }
            out += when {
                countOnly -> "$matchCount match(es) in ${filesWithHits.size} file(s) ($stats)"
                capped -> "[stopped at $maxHits matches — use --max to raise, or --offset ${offset + matchCount} to continue] ($stats)"
                else -> "$matchCount match(es) in ${filesWithHits.size} file(s) ($stats)"
            }
            CommandResult.Success(out)
        }
    },
)

// ───────────────────────────── replace ─────────────────────────────

private suspend fun readPatternFile(ctx: com.zipcli.ke.domain.CommandContext, arg: String): String {
    val path = VirtualPath.resolve(ctx.cwd, arg)
    val entry = ctx.fs.stat(path) ?: throw IllegalArgumentException(notFoundMessage(ctx.fs, path))
    if (entry.isDirectory) throw IllegalArgumentException("$path is a directory")
    return when (val r = readTextStrict(ctx.fs, entry)) {
        // Editors add a final newline; a block pattern normally should not include it.
        is TextRead.Ok -> r.text.removeSuffix("\n").removeSuffix("\r")
        else -> throw IllegalArgumentException(describeSkip(r, path))
    }
}

private val replaceSpec = CommandSpec(
    name = "replace",
    summary = "Replace text (incl. multi-line code blocks) in a file, glob or directory. --dry-run previews",
    usage = "replace <path> <old> <new> [-r] [-i] [-w] [--regex] [--reindent] [--dry-run] [--backup] [--old-file F] [--new-file F] [--fail-on-no-match] [--json]",
    minArgs = 1,
    maxArgs = 3,
    flags = listOf(
        FlagSpec("recursive", 'r', false, "apply to every file under a directory"),
        FlagSpec("ignore-case", 'i', false, "ignore case when matching <old>"),
        FlagSpec("word", 'w', false, "whole-word match"),
        FlagSpec("regex", null, false, "<old> is a regex: ^ and $ match per line, \$1 / \${name} work in <new>"),
        FlagSpec("dotall", null, false, "regex: let . match line breaks"),
        FlagSpec("reindent", null, false, "multi-line: ignore indentation differences when matching and re-indent <new> to fit the file"),
        FlagSpec("dry-run", 'n', false, "show a before/after preview without writing anything"),
        FlagSpec("backup", null, false, "keep the original as <file>.bak (only if no .bak exists yet)"),
        FlagSpec("old-file", null, true, "read <old> from a file (easier than typing a block)"),
        FlagSpec("new-file", null, true, "read <new> from a file"),
        FlagSpec("fail-on-no-match", null, false, "exit status 1 when nothing was replaced (like find)"),
        FlagSpec("json", null, false, "machine-readable result: {replaced, files, dry_run, details:[{path,count}], skipped, errors}"),
    ),
    exitStatus = "0 = done (also when nothing matched, unless --fail-on-no-match), 1 = nothing matched with --fail-on-no-match or another failure, 2 = usage error, 3 = path not found",
    execute = execute@ { ctx ->
        val pathArg = ctx.parsed.arg(0)!!
        val rest = ctx.parsed.args.drop(1).toMutableList()
        val usage = "Usage: replace <path> <old> <new>   (or --old-file/--new-file)"

        val old: String
        val new: String
        try {
            old = ctx.parsed.flagValue("old-file")?.let { readPatternFile(ctx, it) }
                ?: rest.removeFirstOrNull() ?: return@execute CommandResult.Failure(usage)
            new = ctx.parsed.flagValue("new-file")?.let { readPatternFile(ctx, it) }
                ?: rest.removeFirstOrNull() ?: return@execute CommandResult.Failure(usage)
        } catch (e: IllegalArgumentException) {
            return@execute CommandResult.Failure(e.message ?: "Cannot read pattern file")
        }
        if (rest.isNotEmpty()) return@execute CommandResult.Failure(usage)

        val recursive = ctx.parsed.hasFlag("recursive")
        val regexMode = ctx.parsed.hasFlag("regex")
        val dryRun = ctx.parsed.hasFlag("dry-run")
        val backup = ctx.parsed.hasFlag("backup")
        val failOnNoMatch = ctx.parsed.hasFlag("fail-on-no-match")
        val json = ctx.parsed.hasFlag("json")
        val details = ArrayList<Pair<String, Int>>()

        if (old.isEmpty()) return@execute CommandResult.Failure("<old> must not be empty")

        val matcher = try {
            BlockMatcher.build(old, regexMode, ctx.parsed.hasFlag("ignore-case"), ctx.parsed.hasFlag("word"),
                ctx.parsed.hasFlag("reindent"), ctx.parsed.hasFlag("dotall"))
        } catch (e: java.util.regex.PatternSyntaxException) {
            return@execute CommandResult.Failure("Invalid pattern: ${e.description} (at position ${e.index} of '${e.pattern}')", ErrorCodes.USAGE)
        } catch (e: Exception) {
            return@execute CommandResult.Failure("Invalid pattern: ${e.message}", ErrorCodes.USAGE)
        }

        // Resolve targets: a real path, or a wildcard like *.txt
        val literal = VirtualPath.resolve(ctx.cwd, pathArg)
        val roots: List<String> = when {
            ctx.fs.exists(literal) -> listOf(literal)
            Glob.hasWildcard(pathArg) -> Glob.expand(ctx.fs, ctx.cwd, pathArg).map { VirtualPath.resolve(ctx.cwd, it) }
            else -> emptyList()
        }
        if (roots.isEmpty()) {
            return@execute CommandResult.Failure(notFoundMessage(ctx.fs, literal) + ". " + usage)
        }

        val files = ArrayList<FileEntry>()
        for (r in roots) {
            val e = ctx.fs.stat(r) ?: continue
            if (e.isDirectory) {
                if (!recursive) return@execute CommandResult.Failure("$r is a directory; use -r")
                files += ctx.fs.walk(r).filter { !it.isDirectory }
            } else files += e
        }

        var filesChanged = 0
        var totalReplacements = 0
        var skipped = 0
        var errors = 0
        var firstError: String? = null
        val detail = ArrayList<String>()
        val preview = ArrayList<String>()

        for ((i, f) in files.withIndex()) {
            coroutineContext.ensureActive()
            if ((i + 1) % 20 == 0) ctx.progress("replace: ${i + 1}/${files.size} files")
            try {
                val text = when (val r = readTextStrict(ctx.fs, f)) {
                    is TextRead.Ok -> r.text
                    else -> { skipped++; continue }   // never rewrite binary / huge files
                }
                val eol = BlockMatcher.dominantEol(text)
                val (updated, count) = matcher.replaceAll(text, new, eol)
                if (count > 0 && updated != text) {
                    if (dryRun && preview.size < 60) {
                        var shown = 0
                        val counter = LineCounter(text)
                        for (m in matcher.regex.findAll(text)) {
                            if (shown++ >= 5 || preview.size >= 60) break
                            preview += "${f.path}:${counter.lineAt(m.range.first)}"
                            val ls = text.lastIndexOf('\n', m.range.first - 1) + 1
                            val before = text.substring(ls, m.range.first) + m.value
                            val after = text.substring(ls, m.range.first) + matcher.replacementFor(m, new, eol, text)
                            before.split('\n').take(8).forEach { preview += "  - " + snippet(it.trimEnd('\r')) }
                            after.split('\n').take(8).forEach { preview += "  + " + snippet(it.trimEnd('\r')) }
                        }
                    }
                    if (!dryRun) {
                        if (backup && !ctx.fs.exists(f.path + ".bak")) ctx.fs.writeText(f.path + ".bak", text)
                        ctx.fs.writeText(f.path, updated)
                    }
                    filesChanged++
                    totalReplacements += count
                    detail += "  ${f.path}: $count"
                    details += f.path to count
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
            }
        }

        val tail = buildString {
            if (skipped > 0) append(" Skipped $skipped binary/large file(s).")
            if (errors > 0) append(" $errors file(s) failed ($firstError).")
        }
        if (json) {
            val body = Json.obj(
                "replaced" to totalReplacements, "files" to filesChanged, "dry_run" to dryRun,
                "details" to details.map { (p, c) -> Json.Raw(Json.obj("path" to p, "count" to c)) },
                "skipped" to skipped, "errors" to errors, "first_error" to firstError,
            )
            return@execute CommandResult.Success(listOf(body), status = if (filesChanged == 0 && failOnNoMatch) 1 else 0)
        }
        if (filesChanged == 0) {
            val hint = when {
                errors > 0 || regexMode || !old.contains('\n') -> ""
                !ctx.parsed.hasFlag("reindent") -> " Tip: if the indentation may differ, add --reindent."
                else -> " Tip: with --reindent the block must start at the beginning of a line, and every line of it must match (ignoring indentation)."
            }
            CommandResult.Success(listOf("No replacements made.$tail$hint"), status = if (failOnNoMatch) 1 else 0)
        } else {
            val verb = if (dryRun) "Would replace" else "Replaced"
            val out = ArrayList<String>()
            out += preview
            if (files.size > 1 || dryRun) out += detail.take(30)
            if (detail.size > 30) out += "  … and ${detail.size - 30} more file(s) (add --json for the complete list)"
            if (dryRun && filesChanged > 0 && preview.size >= 60) out += "[preview shows the first 60 lines only; the totals below are complete]"
            out += "$verb $totalReplacements occurrence(s) across $filesChanged file(s).$tail" +
                if (dryRun) " Nothing was written." else ""
            CommandResult.Success(out)
        }
    },
)

private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = 0
    while (true) {
        idx = haystack.indexOf(needle, idx)
        if (idx < 0) break
        count++
        idx += needle.length
    }
    return count
}

// ───────────────────────────── clear ─────────────────────────────

private val clearSpec = CommandSpec(
    name = "clear",
    summary = "Clear the terminal log",
    usage = "clear",
    maxArgs = 0,
    execute = { CommandResult.Clear },
)

// ───────────────────────────── history ─────────────────────────────

private val historySpec = CommandSpec(
    name = "history",
    summary = "Show recent commands (default: the last 20; also records commands run over MCP)",
    usage = "history [n]",
    maxArgs = 1,
    execute = execute@ { ctx ->
        val n = if (ctx.parsed.arg(0) == null) 20 else ctx.parsed.arg(0)!!.toIntOrNull() ?: 0
        if (n <= 0) return@execute CommandResult.Failure("Usage: history [n]  (n must be a positive integer; no argument shows the last 20)")
        if (ctx.history.isEmpty()) return@execute CommandResult.Success(listOf("(no commands recorded yet)"))
        val shown = ctx.history.takeLast(n)
        val offset = ctx.history.size - shown.size
        CommandResult.Success(
            shown.mapIndexed { i, cmd ->
                "${(offset + i + 1).toString().padStart(4)}  ${cmd.replace("\n", " ⏎ ")}"
            }
        )
    },
)
