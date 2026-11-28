package dev.ide.playground

import dev.ide.platform.ContentHash
import dev.ide.platform.fileInfo
import dev.ide.platform.listDirectory
import dev.ide.platform.readFile
import dev.ide.vfs.VirtualFile

/** A [VirtualFile] over the portable file system, which in the browser is the in-memory one. */
internal class MemoryFile(override val path: String) : VirtualFile {
    private val info get() = fileInfo(path)

    override val name: String get() = path.trimEnd('/').substringAfterLast('/')
    override val isDirectory: Boolean get() = info?.isDirectory == true
    override val exists: Boolean get() = info != null
    override val length: Long get() = info?.size ?: 0L

    override fun parent(): VirtualFile? {
        val at = path.trimEnd('/').lastIndexOf('/')
        return if (at <= 0) null else MemoryFile(path.substring(0, at))
    }

    // `listDirectory` answers FULL paths, not names.
    override fun children(): List<VirtualFile> =
        if (!isDirectory) emptyList() else listDirectory(path).map { MemoryFile(it) }

    override fun contentHash(): ContentHash = ContentHash.of(readBytes())
    override fun readBytes(): ByteArray = readFile(path) ?: ByteArray(0)
    override fun readText(): CharSequence = readBytes().decodeToString()
}
