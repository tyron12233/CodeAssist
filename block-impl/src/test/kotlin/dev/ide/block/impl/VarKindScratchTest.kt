package dev.ide.block.impl

import dev.ide.block.BlockNode
import dev.ide.block.BlockTree
import dev.ide.block.SlotCategory
import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test

class VarKindScratchTest {

    private val engine = BlockProjectionEngine.withJava()

    @Test
    fun traceVarDeclarationSlotKinds() {
        val src = """
            class A {
                void m() {
                    var n = 1;
                    var s = "x";
                    int k = 2;
                    java.lang.String q = "y";
                }
            }
        """.trimIndent()
        val tree = project(src)
        // dump every LOCAL_VAR block's slots
        for (b in tree.root.descendants()) {
            if (b.kind == NodeKind.LOCAL_VAR) {
                println("LOCAL_VAR block '${b.range}' text-ish slots:")
                b.slots.forEachIndexed { i, s ->
                    println("  slot[$i] cat=${s.category} valueKind=${s.valueKind} children=${s.children.map { c -> "${c.kind.id}/${c.valueKind}" }}")
                }
            }
        }
    }

    private fun project(src: String): BlockTree = engine.project(parse(src))

    private fun parse(src: String): ParsedFile {
        val file = StubFile("/src/A.java", src)
        return analyzer().incrementalParser.parseFull(Snapshot(file, 1, src))
    }

    private fun analyzer(): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = listOf(StubFile("/src"))
            override val classpath: ClasspathSnapshot = EmptyClasspath
            override val bootClasspath: ClasspathSnapshot = bootOf(System.getProperty("java.home"))
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    private fun BlockNode.descendants(): Sequence<BlockNode> = sequence {
        yield(this@descendants)
        for (s in slots) for (c in s.children) yieldAll(c.descendants())
    }

    private class StubFile(override val path: String, private val content: String = "") : VirtualFile {
        override val name get() = path.substringAfterLast('/')
        override val isDirectory = false
        override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }

    private object EmptyClasspath : ClasspathSnapshot {
        override val entries: List<ClasspathEntry> = emptyList()
        override fun fingerprint() = ContentHash("")
    }

    private fun bootOf(vararg paths: String) = object : ClasspathSnapshot {
        override val entries = paths.map { ClasspathEntry(StubFile(it), ClasspathEntryKind.SDK_BOOTCLASSPATH) }
        override fun fingerprint() = ContentHash(paths.joinToString())
    }

    private class Snapshot(
        override val file: VirtualFile,
        override val version: Long,
        override val text: CharSequence,
    ) : DocumentSnapshot {
        override fun length() = text.length
    }
}
