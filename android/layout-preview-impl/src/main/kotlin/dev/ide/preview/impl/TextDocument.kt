package dev.ide.preview.impl

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile

/**
 * A throwaway [DocumentSnapshot] over a plain string, just enough to drive the tolerant `XmlTreeParser`
 * (which reads only `text`). The backing [VirtualFile] is an inert stub — the parser never touches it.
 */
internal class TextDocument(override val text: CharSequence, path: String = "layout.xml") : DocumentSnapshot {
    override val file: VirtualFile = StubFile(path)
    override val version: Long = 0
    override fun length(): Int = text.length

    private class StubFile(override val path: String) : VirtualFile {
        override val name: String get() = path.substringAfterLast('/')
        override val isDirectory: Boolean get() = false
        override val exists: Boolean get() = true
        override val length: Long get() = 0
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = error("stub")
        override fun readBytes(): ByteArray = error("stub")
        override fun readText(): CharSequence = ""
    }
}
