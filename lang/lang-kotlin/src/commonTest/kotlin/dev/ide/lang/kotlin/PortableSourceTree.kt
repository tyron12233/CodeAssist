package dev.ide.lang.kotlin

import dev.ide.platform.createDirectories
import dev.ide.platform.fileInfo
import dev.ide.platform.listDirectory
import dev.ide.platform.readFile
import dev.ide.platform.writeFileAtomically
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile

/**
 * A [VirtualFile] over a real directory, built on the six-operation portable file system.
 *
 * The JVM test suite has `DiskVirtualFile` in `:test-support`, which is `java.nio.file`. This is the same
 * thing where that does not exist, so a source tree the symbol service walks can be a real one on either
 * platform rather than an in-memory stand-in — walking a directory is part of what is being tested.
 */
internal class DiskSourceFile(override val path: String) : VirtualFile {

    private val info get() = fileInfo(path)

    override val name: String get() = path.trimEnd('/').substringAfterLast('/')
    override val isDirectory: Boolean get() = info?.isDirectory == true
    override val exists: Boolean get() = info != null
    override val length: Long get() = info?.size ?: 0L

    override fun parent(): VirtualFile? {
        val at = path.trimEnd('/').lastIndexOf('/')
        return if (at <= 0) null else DiskSourceFile(path.substring(0, at))
    }

    // `listDirectory` answers FULL paths, not names.
    override fun children(): List<VirtualFile> =
        if (!isDirectory) emptyList() else listDirectory(path).map { DiskSourceFile(it) }

    override fun contentHash(): ContentHash = ContentHash.of(readBytes())

    override fun readBytes(): ByteArray = readFile(path) ?: ByteArray(0)

    override fun readText(): CharSequence = readBytes().decodeToString()
}

/** Write [text] as [name] under [dir], creating the directory. Returns the file's path. */
internal fun writeSourceFile(dir: String, name: String, text: String): String {
    createDirectories(dir)
    val path = "$dir/$name"
    check(writeFileAtomically(path, text.encodeToByteArray())) { "could not write $path" }
    return path
}
