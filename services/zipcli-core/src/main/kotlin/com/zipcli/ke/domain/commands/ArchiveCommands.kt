package com.zipcli.ke.domain.commands

import com.zipcli.ke.domain.CommandResult
import com.zipcli.ke.domain.CommandSpec
import com.zipcli.ke.domain.FlagSpec
import com.zipcli.ke.fs.VirtualPath
import com.zipcli.ke.zip.Unzipper
import com.zipcli.ke.zip.Zipper

fun buildArchiveCommands(zipper: Zipper, unzipper: Unzipper): List<CommandSpec> = listOf(
    zipSpec(zipper),
    unzipSpec(unzipper),
)

private fun zipSpec(zipper: Zipper) = CommandSpec(
    name = "zip",
    summary = "Create a zip archive (folders included automatically)",
    usage = "zip <out.zip> <src...> [-r] [-o]",
    minArgs = 2,
    maxArgs = Int.MAX_VALUE,
    expandGlobs = true,
    exitStatus = "0 = archive written, 1 = error (e.g. the destination exists and -o was not given)",
    flags = listOf(
        FlagSpec("recursive", 'r', false, "accepted for convenience; folders are always included"),
        FlagSpec("overwrite", 'o', false, "replace an existing archive"),
    ),
    execute = execute@ { ctx ->
        var dest = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        var addedExt = false
        if (!VirtualPath.name(dest).contains('.')) { dest += ".zip"; addedExt = true }
        val srcs = ctx.parsed.args.drop(1).map { VirtualPath.resolve(ctx.cwd, it) }.distinct()

        for (src in srcs) {
            if (ctx.fs.stat(src) == null) return@execute CommandResult.Failure(notFoundMessage(ctx.fs, src))
            if (src == dest) return@execute CommandResult.Failure("The archive cannot contain itself: $src")
        }
        if (ctx.fs.exists(dest)) {
            if (!ctx.parsed.hasFlag("overwrite")) {
                return@execute CommandResult.Failure("Destination already exists: $dest (add -o/--overwrite to replace it)")
            }
            if (ctx.fs.stat(dest)?.isDirectory == true) {
                return@execute CommandResult.Failure("$dest is a directory")
            }
            try { ctx.fs.delete(dest, recursive = false) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { return@execute CommandResult.Failure("Cannot replace $dest: ${e.message ?: e.javaClass.simpleName}") }
        }

        val stats = try {
            zipper.zip(srcs, dest) { msg -> ctx.progress(msg) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@execute CommandResult.Failure("Zip failed: ${e.message ?: e.javaClass.simpleName}")
        }

        if (stats.files + stats.dirs == 0) {
            return@execute CommandResult.Failure("Nothing was added to $dest")
        }
        val saved = if (stats.bytesIn > 0) {
            val pct = ((1.0 - stats.bytesOut.toDouble() / stats.bytesIn) * 100).toInt()
            if (pct > 0) ", $pct% smaller" else ""
        } else ""
        val out = ArrayList<String>()
        if (addedExt) out += "Added .zip extension"
        out += "Created $dest — ${stats.files} file(s)" +
            (if (stats.dirs > 0) ", ${stats.dirs} empty folder(s)" else "") +
            " · ${formatSize(stats.bytesIn)} → ${formatSize(stats.bytesOut)}$saved" +
            (if (stats.skipped > 0) " · ${stats.skipped} duplicate name(s) skipped" else "")
        CommandResult.Success(out)
    },
)

private fun unzipSpec(unzipper: Unzipper) = CommandSpec(
    name = "unzip",
    summary = "Extract a zip archive (or list it with -l)",
    usage = "unzip <archive.zip> [destDir] [-o] [-l] [--here]",
    minArgs = 1,
    maxArgs = 2,
    flags = listOf(
        FlagSpec("overwrite", 'o', false, "overwrite existing files"),
        FlagSpec("list", 'l', false, "list the contents without extracting"),
        FlagSpec("here", null, false, "extract into the current directory even if the archive has many top-level items"),
    ),
    exitStatus = "0 = extracted or listed, 1 = error (nothing extracted, bad archive, …)",
    execute = execute@ { ctx ->
        var zipPath = VirtualPath.resolve(ctx.cwd, ctx.parsed.arg(0)!!)
        if (!ctx.fs.exists(zipPath) && ctx.fs.exists("$zipPath.zip")) zipPath += ".zip"
        val zipEntry = ctx.fs.stat(zipPath)
            ?: return@execute CommandResult.Failure(notFoundMessage(ctx.fs, zipPath))
        if (zipEntry.isDirectory) return@execute CommandResult.Failure("$zipPath is a directory")

        if (ctx.parsed.hasFlag("list")) {
            ctx.progress("Reading $zipPath…")
            val items = try { unzipper.list(zipPath) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { return@execute CommandResult.Failure("Cannot read archive: ${e.message ?: e.javaClass.simpleName}") }
            if (items.isEmpty()) return@execute CommandResult.Failure("No entries found — not a valid zip archive?")
            val lines = items.take(500).map { "%8s  %s".format(if (it.isDirectory) "-" else formatSize(it.size), it.name) }
            val more = if (items.size > 500) listOf("… and ${items.size - 500} more") else emptyList()
            val files = items.count { !it.isDirectory }
            return@execute CommandResult.Success(
                lines + more + "$files file(s), ${items.size - files} folder(s), ${formatSize(items.sumOf { it.size })} uncompressed")
        }

        // Destination: explicit, or smart. An archive with several top-level items would
        // otherwise spill its contents all over the current directory.
        val explicit = ctx.parsed.arg(1)
        var note: String? = null
        val destDir: String = if (explicit != null) {
            VirtualPath.resolve(ctx.cwd, explicit)
        } else if (ctx.parsed.hasFlag("here")) {
            ctx.cwd
        } else {
            ctx.progress("Inspecting $zipPath…")
            val items = try { unzipper.list(zipPath) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { return@execute CommandResult.Failure("Cannot read archive: ${e.message ?: e.javaClass.simpleName}") }
            val top = items.map { it.name.trimStart('/').substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
            if (top.size > 1) {
                val folder = VirtualPath.name(zipPath).removeSuffix(".zip").removeSuffix(".ZIP").ifEmpty { "extracted" }
                note = "Archive has ${top.size} top-level items; extracting into $folder/ (use --here to extract in place)"
                VirtualPath.resolve(ctx.cwd, folder)
            } else ctx.cwd
        }

        val stats = try {
            unzipper.unzip(zipPath, destDir, ctx.parsed.hasFlag("overwrite")) { msg -> ctx.progress(msg) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@execute CommandResult.Failure("Unzip failed: ${e.message ?: e.javaClass.simpleName}")
        }

        if (stats.files + stats.dirs + stats.skipped == 0) {
            return@execute CommandResult.Failure("No entries found — not a valid zip archive?")
        }
        val out = ArrayList<String>()
        note?.let { out += it }
        out += "Extracted to $destDir — ${stats.files} file(s), ${formatSize(stats.bytes)}" +
            (if (stats.dirs > 0) ", ${stats.dirs} folder(s)" else "")
        if (stats.skipped > 0) out += "Skipped ${stats.skipped} existing file(s); use -o to overwrite"
        CommandResult.Success(out)
    },
)
