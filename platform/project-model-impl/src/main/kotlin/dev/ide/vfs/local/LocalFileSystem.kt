package dev.ide.vfs.local

import dev.ide.platform.ContentHash
import dev.ide.vfs.FileWatch
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.VirtualFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * A minimal [VirtualFileSystem] backed directly by the real local filesystem (`java.nio`).
 *
 * This is the small amount of VFS the project model needs to be concrete and persistable now — a full
 * `vfs-impl` (caching, watching, an in-IDE write journal) is a later module; because the model depends
 * only on the `dev.ide.vfs` interfaces, it can be swapped in without touching the model.
 *
 * [fileFor] returns a handle for a path whether or not it exists on disk (the model legitimately
 * references dirs that are not yet created, e.g. a module's output dir); [findByPath] keeps the VFS
 * contract of returning null for a missing path.
 */
class LocalFileSystem(root: Path) : VirtualFileSystem {
    private val rootPath: Path = root.toAbsolutePath().normalize()
    private val rootFile = LocalVirtualFile(this, rootPath)

    fun fileFor(path: Path): VirtualFile = LocalVirtualFile(this, path.toAbsolutePath().normalize())

    fun fileFor(path: String): VirtualFile = fileFor(Paths.get(path))

    override fun findByPath(path: String): VirtualFile? {
        val p = Paths.get(path).toAbsolutePath().normalize()
        return if (Files.exists(p)) LocalVirtualFile(this, p) else null
    }

    override fun root(): VirtualFile = rootFile

    override fun watch(root: VirtualFile): FileWatch = NoopWatch

    private object NoopWatch : FileWatch {
        override fun dispose() {}
    }
}

/** A [VirtualFile] over a concrete [Path]. Identity (equals/hashCode) is the normalized absolute path. */
class LocalVirtualFile internal constructor(
    private val fs: LocalFileSystem,
    val nioPath: Path,
) : VirtualFile {

    override val path: String get() = nioPath.toString()
    override val name: String get() = nioPath.fileName?.toString() ?: nioPath.toString()
    override val isDirectory: Boolean get() = Files.isDirectory(nioPath)
    override val exists: Boolean get() = Files.exists(nioPath)
    override val length: Long get() = if (exists && !isDirectory) Files.size(nioPath) else 0L

    override fun parent(): VirtualFile? = nioPath.parent?.let { LocalVirtualFile(fs, it) }

    override fun children(): List<VirtualFile> =
        if (isDirectory) {
            Files.list(nioPath).use { stream ->
                stream.sorted().map { LocalVirtualFile(fs, it) }.collect(Collectors.toList())
            }
        } else {
            emptyList()
        }

    override fun contentHash(): ContentHash {
        if (!exists || isDirectory) return ContentHash("")
        return ContentHash.of(readBytes())
    }

    override fun readBytes(): ByteArray = Files.readAllBytes(nioPath)

    override fun readText(): CharSequence = String(readBytes(), Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is LocalVirtualFile && other.nioPath == nioPath
    override fun hashCode(): Int = nioPath.hashCode()
    override fun toString(): String = path
}
