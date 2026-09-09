package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repro for the blank custom-icon preview, using the EXACT generated-icon shape the user reported: the full
 * `path(...)` signature (with the value-class companion constants `StrokeCap.Butt`/`StrokeJoin.Bevel`/
 * `PathFillType.Companion.NonZero`) and the full PathBuilder DSL (`quadTo`/`reflectiveQuadTo`/
 * `reflectiveQuadToRelative`/`quadToRelative`/`lineToRelative`/`moveToRelative`). If any of these fails to
 * interpret, the `path { }` never populates and the built `ImageVector` root is empty -> the Icon is blank.
 */
class RealIconVectorReproTest {


    @Test
    fun realGeneratedIconBuildsItsPath() {
        val code = """
            package demo
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.graphics.PathFillType
            import androidx.compose.ui.graphics.SolidColor
            import androidx.compose.ui.graphics.StrokeCap
            import androidx.compose.ui.graphics.StrokeJoin
            import androidx.compose.ui.graphics.vector.ImageVector
            import androidx.compose.ui.graphics.vector.path
            import androidx.compose.ui.unit.dp

            fun box(): Any = ImageVector.Builder(
                name = "Search",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Bevel,
                    strokeLineMiter = 1f,
                    pathFillType = PathFillType.Companion.NonZero,
                ) {
                    moveTo(9.5f, 16f)
                    quadTo(6.78f, 16f, 4.89f, 14.11f)
                    quadTo(3f, 12.23f, 3f, 9.5f)
                    reflectiveQuadTo(9.5f, 3f)
                    reflectiveQuadToRelative(4.61f, 1.89f)
                    quadToRelative(0f, 1.1f, -0.35f, 2.07f)
                    lineToRelative(5.6f, 5.6f)
                    moveToRelative(0f, -2f)
                    close()
                }
            }.build()
        """.trimIndent()
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val fn = program["box/0"]
        assertTrue(fn != null, "box() must be in the program")
        assertTrue(fn!!.isComplete, "box() failed to lower — a path DSL call or a value-class arg is unsupported; diags=${fn.diagnostics}")
        val result = Interpreter(program, ComposeDispatcher()).call(fn, emptyList())
        assertTrue(result != null && result.javaClass.simpleName == "ImageVector", "box() must build an ImageVector; was $result")
        val root = result!!.javaClass.getMethod("getRoot").invoke(result)
        val size = root.javaClass.getMethod("getSize").invoke(root) as Int
        assertTrue(size >= 1, "the built ImageVector's root is EMPTY (size=$size) — the full path { } DSL did not populate it")
    }

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
