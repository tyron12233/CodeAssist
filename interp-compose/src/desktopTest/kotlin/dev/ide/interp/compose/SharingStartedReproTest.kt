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
import kotlin.test.assertTrue

/**
 * `SharingStarted.WhileSubscribed(5000)` (the `stateIn(started = …)` argument in a ViewModel) must interpret.
 * Its real signature is `fun WhileSubscribed(stopTimeoutMillis: Long = 0, replayExpirationMillis: Long = …)`,
 * so the call omits the second (defaulted) param AND passes an `Int` literal (`5000`) where a `Long` is
 * expected — Kotlin adapts the integer literal to `Long`, but the parse-only lowerer types it `Int`. The
 * preview reported "no method `WhileSubscribed`(1) on …SharingStarted$Companion": the `$default` synthetic was
 * rejected because the interpreter wouldn't accept the `Int` arg for the `Long` param. The dispatcher now
 * admits a numeric arg for a numeric-primitive param and converts it (JVM reflection won't widen Integer→long).
 */
class SharingStartedReproTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun whileSubscribedWithIntLiteralAndDefaultedArg() {
        val code = """
            package demo
            import kotlinx.coroutines.flow.SharingStarted
            fun box(): Any = SharingStarted.WhileSubscribed(5000)
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val box = program["box/0"]!!
        val result = Interpreter(program, ComposeDispatcher()).call(box, emptyList())
        val sharingStarted = Class.forName("kotlinx.coroutines.flow.SharingStarted")
        assertTrue(
            result != null && sharingStarted.isInstance(result),
            "SharingStarted.WhileSubscribed(5000) must build a SharingStarted (Int→Long default-arg call); was $result",
        )
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version = 1L
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
