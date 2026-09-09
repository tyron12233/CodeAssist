package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Isolates the Canvas "no preview" root cause: value-class OPERATOR (`Size.div(Float)`) + PROPERTY (`Size.width`
 * / `.height`) on a `Size`. `drawRect(size = size)` renders (size passes through), but `size / 2F` and
 * `size.width` silently yield garbage → a zero-size draw → blank. These return the underlying `Float`s so the
 * failure is a concrete wrong number, not a blank bitmap.
 */
class SizeValueClassProbeTest {


    private fun run(body: String): Any? {
        val code = "package demo\nimport androidx.compose.ui.geometry.Size\n$body"
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(SDoc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
    }

    @Test fun sizeWidth() =
        assertEquals(80f, run("fun box(): Float { val s = Size(80f, 40f); return s.width }"), "Size.width")

    @Test fun sizeHeight() =
        assertEquals(40f, run("fun box(): Float { val s = Size(80f, 40f); return s.height }"), "Size.height")

    @Test fun sizeDivWidth() =
        assertEquals(40f, run("fun box(): Float { val s = Size(80f, 40f); return (s / 2f).width }"), "(Size / 2f).width")

    @Test fun sizeDivHeight() =
        assertEquals(20f, run("fun box(): Float { val s = Size(80f, 40f); return (s / 2f).height }"), "(Size / 2f).height")
}

private class SDoc(override val text: CharSequence) : DocumentSnapshot {
    override val file: VirtualFile = SF(); override val version = 1L
    override fun length() = text.length
}
private class SF : VirtualFile {
    override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
    override val exists = true; override val length = 0L
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash() = ContentHash("")
    override fun readBytes() = ByteArray(0)
    override fun readText(): CharSequence = ""
}
