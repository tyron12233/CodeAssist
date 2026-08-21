package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: reading a value class's OWN property member — `Dp.value` — crashed the preview with
 * `no static getValue(1) on androidx.compose.ui.unit.Dp` (surfaced as a blank/partial render of e.g. Jetsnack).
 * The interpreter holds a `Dp` as its UNBOXED underlying (`Float`) and routed `.value` to a static `-impl` on
 * the value class — but a value class's own property getter is an INSTANCE method on the box (`Dp.getValue()`;
 * there is NO `getValue-impl`), so the static lookup missed. The fix boxes the underlying and invokes the member
 * on the box for that miss, while operators (static `div-…`/`plus-…`) keep their existing static-impl path.
 */
class ValueClassMemberReadTest {


    private fun run(code: String, entry: String): Any? {
        val trimmed = code.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val program = dev.ide.lang.kotlin.interp.KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program[entry]!!, emptyList())
    }

    @Test
    fun readsDpValueMember() {
        // The reported crash: `.value` on a value-class Dp (an instance-only getter on the box).
        val result = run(
            """
            package demo
            import androidx.compose.ui.unit.Dp
            import androidx.compose.ui.unit.dp
            fun box(): Float {
                val d: Dp = 16.dp
                return d.value
            }
            """,
            "box/0",
        )
        assertEquals(16f, result)
    }

    @Test
    fun dpArithmeticThenValue() {
        // Operators stay on the static `-impl` path (unchanged); the trailing `.value` uses the boxed-instance
        // fallback. Proves the fix didn't disturb value-class arithmetic.
        val result = run(
            """
            package demo
            import androidx.compose.ui.unit.dp
            fun box(): Float = (16.dp + 4.dp).value
            """,
            "box/0",
        )
        assertEquals(20f, result)
    }

    @Test
    fun dpComparison() {
        // `<` compiles through the static `compareTo-…` impl — a guard that the common operator path still works.
        val result = run(
            """
            package demo
            import androidx.compose.ui.unit.dp
            fun box(): Boolean = 16.dp < 20.dp
            """,
            "box/0",
        )
        assertEquals(true, result)
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
