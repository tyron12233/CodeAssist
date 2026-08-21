package dev.ide.interp.compose

import androidx.compose.ui.graphics.vector.ImageVector
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
 * Regression: a generated `ImageVector` icon (a top-level `val icon get()` that lazily builds into a
 * `private var _icon` cache) failed to preview with "Unsupported assignment target: Call" — the `_icon = …`
 * write, whose read had lowered to a synthetic getter Call. Top-level `var` backing fields are now
 * interpreter-storage-backed, so the write lands and the getter's `return _icon!!` reads it back.
 *
 * The `ImageVector.Builder`/`path { }` DSL itself already interprets (see [RealIconVectorReproTest]); what this
 * adds is the lazy-singleton wrapper. The code is indexed as an in-memory source root so the bare `icon`/
 * `more_vert` top-level-property references resolve, exactly as a real project file does.
 */
class ImageVectorLazyCachePreviewTest {


    private fun run(code: String): Any? {
        val trimmed = code.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        return Interpreter(program, ComposeDispatcher()).call(program["box/0"]!!, emptyList())
    }

    @Test
    fun lazyImageVectorBuilderBuildsAndCaches() {
        val result = run(
            """
            package demo
            import androidx.compose.ui.graphics.vector.ImageVector
            import androidx.compose.ui.unit.dp
            private var _icon: ImageVector? = null
            val icon: ImageVector
                get() {
                    if (_icon != null) return _icon!!
                    _icon = ImageVector.Builder(
                        name = "icon",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                    ).build()
                    return _icon!!
                }
            fun box(): Any = icon
            """,
        )
        assertEquals("icon", (result as? ImageVector)?.name, "the lazy ImageVector getter must build + cache a real ImageVector")
    }

    @Test
    fun lazyImageVectorWithPathDsl() {
        // The full generated-icon shape: `.apply { path(...) { moveTo/…/close } }.build()`.
        val result = run(
            """
            package demo
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.graphics.SolidColor
            import androidx.compose.ui.graphics.vector.ImageVector
            import androidx.compose.ui.graphics.vector.path
            import androidx.compose.ui.unit.dp
            private var _more_vert: ImageVector? = null
            val more_vert: ImageVector
                get() {
                    if (_more_vert != null) return _more_vert!!
                    _more_vert = ImageVector.Builder(
                        name = "more_vert",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                    ).apply {
                        path(fill = SolidColor(Color.Black)) {
                            moveTo(12f, 20f)
                            quadToRelative(-0.82f, 0f, -1.41f, -0.59f)
                            reflectiveQuadTo(10f, 18f)
                            close()
                        }
                    }.build()
                    return _more_vert!!
                }
            fun box(): Any = more_vert
            """,
        )
        assertEquals("more_vert", (result as? ImageVector)?.name, "the generated-icon path DSL must build a real ImageVector")
    }

    // --- an in-memory source root, so the service indexes the file and the bare property refs resolve ---
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
