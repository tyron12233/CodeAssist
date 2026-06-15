package dev.ide.lang.kotlin

import dev.ide.lang.dom.DomNode
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
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
    override val file: VirtualFile = FakeFile("src/Main.kt"),
    override val version: Long = 1,
) : DocumentSnapshot {
    override fun length(): Int = text.length
}

fun parse(kotlin: String, path: String = "src/Main.kt"): KotlinParsedFile =
    KotlinIncrementalParser().parseFull(TestDoc(kotlin, FakeFile(path))) as KotlinParsedFile

/** Pre-order flatten of the neutral DOM, for assertions. */
fun DomNode.flatten(): List<DomNode> = buildList {
    fun walk(n: DomNode) { add(n); n.children.forEach(::walk) }
    walk(this@flatten)
}
