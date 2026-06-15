package dev.ide.lang.xml

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile

/** A bare [VirtualFile] backed only by a path — enough for parser/completion tests. */
class FakeFile(override val path: String) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/')
    override val isDirectory: Boolean = false
    override val exists: Boolean = true
    override val length: Long = 0
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash("")
    override fun readBytes(): ByteArray = ByteArray(0)
    override fun readText(): CharSequence = ""
}

class TestDoc(
    override val text: CharSequence,
    override val file: VirtualFile = FakeFile("res/layout/test.xml"),
    override val version: Long = 1,
) : DocumentSnapshot {
    override fun length(): Int = text.length
}

fun parse(xml: String): XmlParsedFile =
    XmlIncrementalParser().parseFull(TestDoc(xml)) as XmlParsedFile
