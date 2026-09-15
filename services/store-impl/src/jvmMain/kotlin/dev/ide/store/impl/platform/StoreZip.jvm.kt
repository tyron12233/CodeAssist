package dev.ide.store.impl.platform

import java.io.File
import java.util.zip.ZipFile

internal actual class ZipArchive(private val zip: ZipFile) {

    private val ordered = zip.entries().toList()

    actual fun entries(): List<ZipEntryInfo> = ordered.mapIndexed { i, e ->
        ZipEntryInfo(name = e.name, isDirectory = e.isDirectory, size = e.size.coerceAtLeast(0), index = i)
    }

    actual fun extractTo(entry: ZipEntryInfo, destPath: String): Long {
        val source = ordered.getOrNull(entry.index) ?: return -1
        return runCatching {
            zip.getInputStream(source).use { input ->
                File(destPath).outputStream().buffered().use { out -> input.copyTo(out) }
            }
        }.getOrElse { -1 }
    }

    actual fun close() {
        runCatching { zip.close() }
    }
}

internal actual fun openZip(path: String): ZipArchive? =
    runCatching { ZipArchive(ZipFile(File(path))) }.getOrNull()
