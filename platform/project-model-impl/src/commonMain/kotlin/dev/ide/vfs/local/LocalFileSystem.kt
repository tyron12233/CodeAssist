package dev.ide.vfs.local

import dev.ide.platform.ContentHash
import dev.ide.platform.FileInfo
import dev.ide.platform.fileInfo
import dev.ide.platform.fileName
import dev.ide.platform.listDirectory
import dev.ide.platform.normalizePath
import dev.ide.platform.parentPath
import dev.ide.platform.readFile
import dev.ide.vfs.FileWatch
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.VirtualFileSystem

/**
 * A minimal [VirtualFileSystem] backed directly by the real local filesystem.
 *
 * This is the small amount of VFS the project model needs to be concrete and persistable now — a full
 * `vfs-impl` (caching, watching, an in-IDE write journal) is a later module; because the model depends only
 * on the `dev.ide.vfs` interfaces, it can be swapped in without touching the model.
 *
 * Paths are strings and the six file operations come from `:model-api`, so this runs wherever the model
 * does. It used to be `java.nio` and a `Path`, which is what kept the whole model on the JVM. JVM callers
 * that hold a `Path` keep working: see the `fileFor` extension in `JvmPaths.kt`.
 *
 * [fileFor] returns a handle for a path whether or not it exists on disk (the model legitimately references
 * dirs that are not yet created, e.g. a module's output dir); [findByPath] keeps the VFS contract of
 * returning null for a missing path.
 */
class LocalFileSystem(root: String) : VirtualFileSystem {
    private val rootPath: String = normalizePath(root)
    private val rootFile = LocalVirtualFile(this, rootPath)

    fun fileFor(path: String): VirtualFile = LocalVirtualFile(this, normalizePath(path))

    override fun findByPath(path: String): VirtualFile? {
        val p = normalizePath(path)
        return if (fileInfo(p) != null) LocalVirtualFile(this, p) else null
    }

    override fun root(): VirtualFile = rootFile

    override fun watch(root: VirtualFile): FileWatch = NoopWatch

    private object NoopWatch : FileWatch {
        override fun dispose() {}
    }
}

/** A [VirtualFile] over a concrete path. Identity (equals/hashCode) is the normalized absolute path. */
class LocalVirtualFile internal constructor(
    private val fs: LocalFileSystem,
    override val path: String,
) : VirtualFile {

    private val info: FileInfo? get() = fileInfo(path)

    override val name: String get() = fileName(path).ifEmpty { path }
    override val isDirectory: Boolean get() = info?.isDirectory == true
    override val exists: Boolean get() = info != null
    override val length: Long get() = info?.takeIf { !it.isDirectory }?.size ?: 0L

    override fun parent(): VirtualFile? = parentPath(path)?.let { LocalVirtualFile(fs, it) }

    // Sorted, because the old `Files.list().sorted()` was and callers (the model's source-set walk, the
    // file tree) depend on a stable order across runs.
    override fun children(): List<VirtualFile> =
        if (isDirectory) listDirectory(path).sorted().map { LocalVirtualFile(fs, it) } else emptyList()

    override fun contentHash(): ContentHash {
        if (!exists || isDirectory) return ContentHash("")
        return ContentHash.of(readBytes())
    }

    override fun readBytes(): ByteArray = readFile(path) ?: ByteArray(0)

    override fun readText(): CharSequence = readBytes().decodeToString()

    override fun equals(other: Any?): Boolean = other is LocalVirtualFile && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path
}
