package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Kotlin primitive-companion constant — `Float.POSITIVE_INFINITY`, `Int.MAX_VALUE`, `Double.NaN`, … — must
 * read as its value in the preview interpreter. These are `const val`s on `Float.Companion`/`Int.Companion`/…
 * that the compiler inlines but the parse-only lowerer keeps as a `PropertyGet` on the (unloadable) `kotlin.Float`
 * type; the interpreter threw "cannot load `kotlin.Float`" (the reported `Offset(Float.POSITIVE_INFINITY, …)`
 * gradient failure). The consts live as static fields on the JVM wrapper (`java.lang.Float`), so the read now
 * routes there.
 */
class FloatConstReproTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    private fun eval(expr: String): Any? {
        val code = "package demo\nfun box(): Any = $expr"
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
    }

    @Test fun floatPositiveInfinity() { assertEquals(Float.POSITIVE_INFINITY, eval("Float.POSITIVE_INFINITY")) }
    @Test fun floatNegativeInfinity() { assertEquals(Float.NEGATIVE_INFINITY, eval("Float.NEGATIVE_INFINITY")) }
    @Test fun intMaxValue() { assertEquals(Int.MAX_VALUE, eval("Int.MAX_VALUE")) }
    @Test fun longMinValue() { assertEquals(Long.MIN_VALUE, eval("Long.MIN_VALUE")) }
    @Test fun doubleNaN() { assertEquals(true, (eval("Double.NaN") as? Double)?.isNaN()) }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
