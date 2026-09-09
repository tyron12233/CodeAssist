package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.interp.InterpreterException
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Two runtime reads whose failure messages named the victim instead of the cause.
 *
 * A device report read, in full, `InterpreterException: cannot read property \`primary\` on a null receiver` —
 * with no indication of which read returned the null, what it was read off, or where it was written. That is
 * unactionable: the property that could not be read is the one place the defect ISN'T. Both the read and write
 * paths now name the receiver expression and its offsets, and a `@Composable` property read with no live
 * composer says so rather than claiming the property does not exist.
 */
class RuntimeReadDiagnosticsTest {

    /** Lower [code] and interpret [entry] OUTSIDE any composition, returning the failure it raises. */
    private fun failureOf(code: String, entry: String): InterpreterException {
        val trimmed = code.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val fn = program[entry] ?: error("`$entry` not lowered; keys=${program.keys}")
        return assertFailsWith<InterpreterException> {
            Interpreter(program, ComposeDispatcher(), classes = lowering.classes(parsed)).call(fn, emptyList())
        }
    }

    /**
     * A null receiver names what produced the null. (The source dereferences a nullable without a check, which
     * the compiler would reject — the interpreter reaches this state from code paths the editor's own checks
     * let through, and the message is what a field report has to work from.)
     */
    @Test
    fun aNullReceiverNamesTheReadThatProducedIt() {
        val message = failureOf(
            """
            package demo
            class Inner(val primary: Int)
            class Holder {
                val inner: Inner? = null
            }
            fun box(): Any? = Holder().inner.primary
            """,
            "box/0",
        ).message.orEmpty()
        assertTrue("cannot read property `primary` on a null receiver" in message, "keeps the original fact; got: $message")
        assertTrue("a read of `inner`" in message, "must name the read that returned null; got: $message")
        assertTrue("demo.Holder" in message, "must name what it was read off; got: $message")
    }

    /**
     * `MaterialTheme.colorScheme`'s getter takes a `Composer`, so reading it outside a composition cannot work
     * — but the message used to be "no readable property `colorScheme` on MaterialTheme", which reads as "that
     * property doesn't exist" and sends the reader looking in the wrong place.
     */
    @Test
    fun aComposablePropertyReadOutsideCompositionSaysSo() {
        val message = failureOf(
            """
            package demo
            import androidx.compose.material3.MaterialTheme
            fun box(): Any? = MaterialTheme.colorScheme
            """,
            "box/0",
        ).message.orEmpty()
        assertTrue("@Composable" in message, "must name the reason; got: $message")
        assertTrue("Composer" in message, "must say a live Composer is what's missing; got: $message")
        assertTrue("no readable property" !in message, "must not claim the property doesn't exist; got: $message")
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
        override val file: VirtualFile = MemFile("Main.kt", text.toString())
        override val version: Long = 1
        override fun length(): Int = text.length
    }
}
