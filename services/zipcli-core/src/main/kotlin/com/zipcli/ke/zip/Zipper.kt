package com.zipcli.ke.zip

import com.zipcli.ke.fs.FileEntry
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

class ZipStats(
    var files: Int = 0,
    var dirs: Int = 0,
    var skipped: Int = 0,
    var bytesIn: Long = 0,
    var bytesOut: Long = 0,
)

/** Counts the bytes that actually reach the destination (= compressed size + headers). */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}

class Zipper(private val fs: TerminalFileSystem) {

    private val bufferSize = 64 * 1024

    suspend fun zip(
        sourcePaths: List<String>,
        destZipPath: String,
        onProgress: (String) -> Unit = {},
    ): ZipStats {
        val dest = VirtualPath.normalize(destZipPath)
        val stats = ZipStats()
        val seen = HashSet<String>()
        var ok = false
        try {
            val raw = fs.openWrite(dest)
            val counting = CountingOutputStream(raw)
            ZipOutputStream(BufferedOutputStream(counting, bufferSize)).use { zos ->
                for (src in sourcePaths) {
                    coroutineContext.ensureActive()
                    val entry = fs.stat(src) ?: continue
                    val name = VirtualPath.name(src)
                    if (entry.isDirectory) zipDirectory(src, name, zos, dest, seen, stats, onProgress)
                    else zipFile(entry, name, zos, seen, stats, onProgress)
                }
            }
            stats.bytesOut = counting.count
            ok = true
        } finally {
            // A cancelled or failed run must not leave a half-written, corrupt archive behind.
            if (!ok) withContext(NonCancellable) { try { fs.delete(dest, false) } catch (_: Exception) {} }
        }
        return stats
    }

    private suspend fun zipDirectory(
        dirPath: String,
        prefix: String,
        zos: ZipOutputStream,
        dest: String,
        seen: MutableSet<String>,
        stats: ZipStats,
        onProgress: (String) -> Unit,
    ) {
        val children = fs.list(dirPath).filter { it.path != dest }   // never put the archive into itself
        if (children.isEmpty()) {
            if (seen.add("$prefix/")) {
                zos.putNextEntry(ZipEntry("$prefix/"))
                zos.closeEntry()
                stats.dirs++
            }
            return
        }
        for (child in children.sortedBy { it.name }) {
            coroutineContext.ensureActive()
            val entryName = "$prefix/${child.name}"
            if (child.isDirectory) zipDirectory(child.path, entryName, zos, dest, seen, stats, onProgress)
            else zipFile(child, entryName, zos, seen, stats, onProgress)
        }
    }

    private suspend fun zipFile(
        file: FileEntry,
        entryName: String,
        zos: ZipOutputStream,
        seen: MutableSet<String>,
        stats: ZipStats,
        onProgress: (String) -> Unit,
    ) {
        if (!seen.add(entryName)) {
            stats.skipped++
            onProgress("Skipped duplicate $entryName")
            return
        }
        onProgress("Adding $entryName")
        // Already-compressed formats gain nothing from deflate; skip the CPU work.
        zos.setLevel(if (isAlreadyCompressed(entryName)) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
        val ze = ZipEntry(entryName)
        if (file.lastModified > 0) ze.time = file.lastModified
        zos.putNextEntry(ze)
        fs.openRead(file.path).use { input ->
            val buffer = ByteArray(bufferSize)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                coroutineContext.ensureActive()
                zos.write(buffer, 0, len)
                stats.bytesIn += len
            }
        }
        zos.closeEntry()
        stats.files++
    }

    companion object {
        private val INCOMPRESSIBLE = setOf(
            "zip", "jar", "apk", "aab", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "lz4",
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
            "mp3", "aac", "m4a", "ogg", "opus", "flac", "wma",
            "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp",
            "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub",
        )

        fun isAlreadyCompressed(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            return dot >= 0 && name.substring(dot + 1).lowercase() in INCOMPRESSIBLE
        }
    }
}
