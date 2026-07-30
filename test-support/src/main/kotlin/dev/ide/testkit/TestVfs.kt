package dev.ide.testkit

import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [VirtualFile] backed by a real filesystem path — enough for source-root walking and classpath reads in
 * tests. Replaces the byte-identical `DiskFile` copies in interp-core / lang-kotlin and similar stubs.
 */
class DiskVirtualFile(val p: Path) : VirtualFile {
    override val path: String get() = p.toString()
    override val name: String get() = p.fileName?.toString() ?: p.toString()
    override val isDirectory: Boolean get() = Files.isDirectory(p)
    override val exists: Boolean get() = Files.exists(p)
    override val length: Long get() = if (exists && !isDirectory) Files.size(p) else 0
    override fun parent(): VirtualFile? = p.parent?.let { DiskVirtualFile(it) }
    override fun children(): List<VirtualFile> =
        if (isDirectory) Files.list(p).use { s -> s.toList() }.map { DiskVirtualFile(it) } else emptyList()
    override fun contentHash(): ContentHash = ContentHash("")
    override fun readBytes(): ByteArray = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
    override fun readText(): CharSequence = if (exists && !isDirectory) Files.readString(p) else ""
}

/**
 * An in-memory [VirtualFile] identified by [path] with fixed [content] and no disk backing. Replaces the
 * many `StubFile` / `FakeFile` / `PathOnlyFile` stubs. [parent]/[children] are absent by default.
 */
class InMemoryVirtualFile(
    override val path: String,
    private val content: String = "",
    override val isDirectory: Boolean = false,
) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/')
    override val exists: Boolean get() = true
    override val length: Long get() = content.length.toLong()
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash(content.hashCode().toString())
    override fun readBytes(): ByteArray = content.toByteArray()
    override fun readText(): CharSequence = content
}

/** A [DiskVirtualFile] over [p]. */
fun virtualFile(p: Path): DiskVirtualFile = DiskVirtualFile(p)

/** An [InMemoryVirtualFile] at [path] with [content]. */
fun virtualFile(path: String, content: String = ""): InMemoryVirtualFile = InMemoryVirtualFile(path, content)
