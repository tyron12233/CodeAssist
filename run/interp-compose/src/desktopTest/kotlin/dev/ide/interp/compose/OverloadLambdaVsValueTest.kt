package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Regression for Jetsnack `Snacks.kt` `Modifier.offsetGradientBackground(colors, width = { … }, offset = { … })`:
 * `unresolved/ambiguous call offsetGradientBackground (candidates=4)`. There are two overloads — one with
 * `Float` width/offset, one with `Density.() -> Float` lambdas — and the call passes lambdas, which fit ONLY the
 * lambda overload. The resolver must reject the `Float` overload (a lambda can't be a Float) and pick the lambda one.
 */
class OverloadLambdaVsValueTest {


    private fun run(code: String, entry: String): Any? {
        val trimmed = code.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val program = dev.ide.lang.kotlin.interp.KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program[entry]!!, emptyList())
    }

    @Test
    fun picksLambdaOverloadForLambdaArgs() {
        val r = run(
            """
            package demo
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.Density
            import androidx.compose.ui.graphics.Color
            fun Modifier.grad(colors: List<Color>, width: Float, offset: Float = 0f): Modifier = this
            fun Modifier.grad(colors: List<Color>, width: Density.() -> Float, offset: Density.() -> Float = { 0f }): Modifier = this
            fun box(): Modifier = Modifier.grad(colors = emptyList(), width = { 6f }, offset = { 0f })
            """,
            "box/0",
        )
        assertNotNull(r, "the lambda-typed overload must be chosen for lambda args")
    }

    private class MemDir(private val kids: List<VirtualFile>) : VirtualFile {
        override val path = "src"; override val name = "src"; override val isDirectory = true
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = kids
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
    private class MemFile(override val name: String, private val content: String) : VirtualFile {
        override val path = name; override val isDirectory = false; override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }
    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = MemFile("Main.kt", text.toString()); override val version = 1L
        override fun length() = text.length
    }
}
