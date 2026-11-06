package com.zipcli.ke.zip

import com.zipcli.ke.fs.FsException
import com.zipcli.ke.fs.TerminalFileSystem
import com.zipcli.ke.fs.VirtualPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class UnzipStats(
    var files: Int = 0,
    var dirs: Int = 0,
    var skipped: Int = 0,
    var bytes: Long = 0,
)

data class ZipItem(val name: String, val size: Long, val isDirectory: Boolean)

class Unzipper(private val fs: TerminalFileSystem) {

    private val bufferSize = 64 * 1024

    /** Entry names and uncompressed sizes. Reads the whole archive once (zip streams have no index). */
    suspend fun list(zipPath: String): List<ZipItem> {
        val items = ArrayList<ZipItem>()
        fs.openRead(zipPath).use { raw ->
            ZipInputStream(BufferedInputStream(raw, bufferSize)).use { zis ->
                val buffer = ByteArray(bufferSize)
                var entry = zis.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    var size = 0L
                    if (!entry.isDirectory) {
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) size += len
                    }
                    items += ZipItem(entry.name.replace('\\', '/'), size, entry.isDirectory)
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return items
    }

    suspend fun unzip(
        zipPath: String,
        destDir: String,
        overwrite: Boolean,
        onProgress: (String) -> Unit = {},
    ): UnzipStats {
        val dest = VirtualPath.normalize(destDir)
        val stats = UnzipStats()
        if (!fs.exists(dest)) fs.mkdirs(dest)
        val prefix = if (dest == "/") "/" else "$dest/"

        fs.openRead(zipPath).use { raw ->
            ZipInputStream(BufferedInputStream(raw, bufferSize)).use { zis ->
                val buffer = ByteArray(bufferSize)
                var entry = zis.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()

                    val name = entry.name.replace('\\', '/')
                    // Zip Slip guard: the resolved path must stay inside destDir
                    val targetPath = VirtualPath.resolve(dest, name)
                    if (!targetPath.startsWith(prefix) && targetPath != dest) {
                        throw FsException("Blocked unsafe zip entry: $name")
                    }

                    if (entry.isDirectory) {
                        if (!fs.exists(targetPath)) { fs.mkdirs(targetPath); stats.dirs++ }
                    } else if (!overwrite && fs.exists(targetPath)) {
                        stats.skipped++
                        onProgress("Skipped $targetPath (exists)")
                    } else {
                        onProgress("Extracting $targetPath")
                        val parent = targetPath.substringBeforeLast('/', "")
                        if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
                        var complete = false
                        try {
                            fs.openWrite(targetPath, append = false).use { out ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    coroutineContext.ensureActive()
                                    out.write(buffer, 0, len)
                                    stats.bytes += len
                                }
                            }
                            complete = true
                        } finally {
                            // Do not leave a truncated file behind after an error or cancel.
                            if (!complete) withContext(NonCancellable) { try { fs.delete(targetPath, false) } catch (_: Exception) {} }
                        }
                        stats.files++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return stats
    }
}
