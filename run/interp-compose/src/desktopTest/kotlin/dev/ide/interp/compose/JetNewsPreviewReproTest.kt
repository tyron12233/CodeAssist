package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.children
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Second-layer JetNews preview failures seen on the emulator (2026-09-09) once the files lowered: each case is
 * the exact JetNews shape against real Compose on the desktop test classpath.
 */
class JetNewsPreviewReproTest {

    /** `Markup.toAnnotatedStringItem`: `when (this.type)` over a source enum whose branches build
     *  `AnnotatedString.Range(typography.bodyLarge.copy(…).toSpanStyle(), start, end)`; on device the `when`
     *  produced `kotlin.Unit` ("Unit cannot be cast to AnnotatedString$Range"). */
    @Test
    fun annotatedStringRangeBranchesOfAWhenOverASourceEnumProduceRanges() {
        val code = """
            package demo
            import androidx.compose.material3.Typography
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.AnnotatedString
            import androidx.compose.ui.text.SpanStyle
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.font.FontStyle
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.text.style.TextDecoration

            enum class MarkupType { Link, Code, Italic, Bold }
            data class Markup(val type: MarkupType, val start: Int, val end: Int, val href: String? = null)
            data class Paragraph(val text: String, val markups: List<Markup> = emptyList())

            private fun paragraphToAnnotatedString(paragraph: Paragraph, typography: Typography, codeBlockBackground: Color): AnnotatedString {
                val styles: List<AnnotatedString.Range<SpanStyle>> = paragraph.markups
                    .map { it.toAnnotatedStringItem(typography, codeBlockBackground) }
                return AnnotatedString(text = paragraph.text, spanStyles = styles)
            }

            fun Markup.toAnnotatedStringItem(typography: Typography, codeBlockBackground: Color): AnnotatedString.Range<SpanStyle> {
                return when (this.type) {
                    MarkupType.Italic -> {
                        AnnotatedString.Range(
                            typography.bodyLarge.copy(fontStyle = FontStyle.Italic).toSpanStyle(),
                            start,
                            end,
                        )
                    }

                    MarkupType.Link -> {
                        AnnotatedString.Range(
                            typography.bodyLarge.copy(textDecoration = TextDecoration.Underline).toSpanStyle(),
                            start,
                            end,
                        )
                    }

                    MarkupType.Bold -> {
                        AnnotatedString.Range(
                            typography.bodyLarge.copy(fontWeight = FontWeight.Bold).toSpanStyle(),
                            start,
                            end,
                        )
                    }

                    MarkupType.Code -> {
                        AnnotatedString.Range(
                            typography.bodyLarge
                                .copy(
                                    background = codeBlockBackground,
                                    fontFamily = FontFamily.Monospace,
                                ).toSpanStyle(),
                            start,
                            end,
                        )
                    }
                }
            }

            fun box(): Any = paragraphToAnnotatedString(
                Paragraph("hello world code", listOf(Markup(MarkupType.Bold, 0, 5), Markup(MarkupType.Code, 12, 16), Markup(MarkupType.Link, 6, 11), Markup(MarkupType.Italic, 0, 2))),
                Typography(),
                Color(0x22000000),
            ).spanStyles.size
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val diags = program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "every function must lower cleanly; diags=$diags")
        val result = Interpreter(program, ComposeDispatcher(), classes = classes).call(program.getValue("box/0"), emptyList())
        assertTrue(result == 4, "all four markups must become span-style ranges; got $result")
    }

    /** The same `toAnnotatedStringItem` shape with JetNews's FILE SPLIT: the enum + data classes in `model/Post.kt`,
     *  the sample post in `data/PostsData.kt` (a top-level `val` built from `listOf(Markup(MarkupType.…))`), the
     *  extension + preview in the entry file, so the enum entries the `when` compares against are read from a
     *  cross-file class and the compared values were constructed in a third file. */
    @Test
    fun annotatedStringRangesAcrossTheJetNewsFileSplit() {
        val model = """
            package demo.model
            enum class MarkupType { Link, Code, Italic, Bold }
            data class Markup(val type: MarkupType, val start: Int, val end: Int, val href: String? = null)
            data class Paragraph(val text: String, val markups: List<Markup> = emptyList())
            data class Post(val id: String, val paragraphs: List<Paragraph>)
        """.trimIndent() + "\n"
        val data = """
            package demo.data
            import demo.model.Markup
            import demo.model.MarkupType
            import demo.model.Paragraph
            import demo.model.Post
            val paragraphsPost3 = listOf(
                Paragraph("hello world code", listOf(Markup(MarkupType.Bold, 0, 5), Markup(MarkupType.Code, 12, 16))),
                Paragraph("second", listOf(Markup(MarkupType.Link, 0, 6, "https://x"), Markup(MarkupType.Italic, 0, 2))),
            )
            val post3 = Post(id = "p3", paragraphs = paragraphsPost3)
        """.trimIndent() + "\n"
        val entry = """
            package demo.ui
            import androidx.compose.material3.Typography
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.AnnotatedString
            import androidx.compose.ui.text.SpanStyle
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.font.FontStyle
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.text.style.TextDecoration
            import demo.data.post3
            import demo.model.Markup
            import demo.model.MarkupType
            import demo.model.Paragraph

            private fun paragraphToAnnotatedString(paragraph: Paragraph, typography: Typography, codeBlockBackground: Color): AnnotatedString {
                val styles: List<AnnotatedString.Range<SpanStyle>> = paragraph.markups
                    .map { it.toAnnotatedStringItem(typography, codeBlockBackground) }
                return AnnotatedString(text = paragraph.text, spanStyles = styles)
            }

            fun Markup.toAnnotatedStringItem(typography: Typography, codeBlockBackground: Color): AnnotatedString.Range<SpanStyle> {
                return when (this.type) {
                    MarkupType.Italic -> { AnnotatedString.Range(typography.bodyLarge.copy(fontStyle = FontStyle.Italic).toSpanStyle(), start, end) }
                    MarkupType.Link -> { AnnotatedString.Range(typography.bodyLarge.copy(textDecoration = TextDecoration.Underline).toSpanStyle(), start, end) }
                    MarkupType.Bold -> { AnnotatedString.Range(typography.bodyLarge.copy(fontWeight = FontWeight.Bold).toSpanStyle(), start, end) }
                    MarkupType.Code -> { AnnotatedString.Range(typography.bodyLarge.copy(background = codeBlockBackground, fontFamily = FontFamily.Monospace).toSpanStyle(), start, end) }
                }
            }

            fun box(): Any = post3.paragraphs.sumOf { paragraphToAnnotatedString(it, Typography(), Color(0x22000000)).spanStyles.size }
        """.trimIndent() + "\n"
        val dir = java.nio.file.Files.createTempDirectory("jetnews-split")
        fun write(rel: String, text: String) {
            val f = dir.resolve(rel); java.nio.file.Files.createDirectories(f.parent); java.nio.file.Files.writeString(f, text)
        }
        write("model/Post.kt", model); write("data/PostsData.kt", data); write("ui/PostContent.kt", entry)
        val service = previewSymbolService(listOf(Disk(dir)))
        val parsed = KotlinIncrementalParser().parseFull(DiskDoc(dir.resolve("ui/PostContent.kt"))) as KotlinParsedFile
        val model2 = KotlinPreviewLowering(service).crossFileModel(parsed)
        val diags = model2.program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "every function must lower cleanly; diags=$diags")
        val result = Interpreter(model2.program, ComposeDispatcher(), classes = model2.classes).call(model2.program.getValue("box/0"), emptyList())
        assertTrue(result == 4, "all four markups across the three files must become span-style ranges; got $result")
    }

    /** `PreviewPostDrawer`: `PostScreen(post, false, {}, false, {})` must bind to the seven-parameter `Post`
     *  overload (two defaulted Compose-typed params), not the exact-arity `PostUiState` one whose 4th parameter
     *  is a function type: on device the latter's body ran ("no property `loading` on source class …Post"). */
    @Test
    fun postScreenPreviewBindsTheDefaultedPostOverload() {
        val code = """
            package demo
            import androidx.compose.foundation.lazy.LazyListState
            import androidx.compose.foundation.lazy.rememberLazyListState
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import kotlinx.coroutines.runBlocking

            data class Post(val id: String)
            data class PostUiState(val post: Post? = null, val loading: Boolean = false)
            sealed class Result<out R> {
                data class Success<out T>(val data: T) : Result<T>()
                data class Error(val exception: Exception) : Result<Nothing>()
            }
            class Repo { suspend fun getPost(postId: String?): Result<Post> = Result.Success(Post(postId ?: "")) }
            val post3 = Post("p3")

            @Composable
            fun PostScreen(uiState: PostUiState, isExpandedScreen: Boolean, onBack: () -> Unit, onToggleFavorite: () -> Unit, onScroll: (index: Int, offset: Int) -> Unit) {
                if (uiState.loading) Text("loading") else Text("ui")
            }

            @Composable
            fun PostScreen(post: Post, isExpandedScreen: Boolean, onBack: () -> Unit, isFavorite: Boolean, onToggleFavorite: () -> Unit, modifier: Modifier = Modifier, lazyListState: LazyListState = rememberLazyListState()) {
                Text(post.id)
            }

            @Composable
            fun box() {
                val post = runBlocking {
                    (Repo().getPost(post3.id) as Result.Success).data
                }
                PostScreen(post, false, {}, false, {})
            }
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = program.getValue("box/0")
        assertTrue(entry.isComplete, "the preview must lower; diags=${entry.diagnostics}")
        val calls = ArrayList<String>()
        fun walk(n: dev.ide.lang.kotlin.interp.RNode) {
            if (n is dev.ide.lang.kotlin.interp.RNode.Call && n.callee.displayName == "PostScreen") calls += (n.callee as dev.ide.lang.kotlin.interp.ResolvedCallable.Source).declId
            n.children().forEach(::walk)
        }
        walk(entry.body)
        assertTrue(calls.size == 1 && calls[0].endsWith("/7"), "the call must bind to the 7-param Post overload; bound to $calls")
    }

    /** `InterestsAdaptiveContentLayout`: a `Layout` whose measure policy measures every child once and places
     *  them in rows from the `placeables` it captured; on device the render died with Compose's "Asking for
     *  measurement result of unmeasured layout modifier". */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun adaptiveContentLayoutMeasuresAndPlacesItsChildren() {
        val code = """
            package demo
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.size
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.layout.Layout
            import androidx.compose.ui.unit.Dp
            import androidx.compose.ui.unit.constrainHeight
            import androidx.compose.ui.unit.constrainWidth
            import androidx.compose.ui.unit.dp
            import kotlin.math.max

            @Composable
            private fun InterestsAdaptiveContentLayout(
                modifier: Modifier = Modifier,
                topPadding: Dp = 0.dp,
                itemSpacing: Dp = 4.dp,
                itemMaxWidth: Dp = 450.dp,
                multipleColumnsBreakPoint: Dp = 600.dp,
                content: @Composable () -> Unit,
            ) {
                Layout(modifier = modifier, content = content) { measurables, outerConstraints ->
                    val multipleColumnsBreakPointPx = multipleColumnsBreakPoint.roundToPx()
                    val topPaddingPx = topPadding.roundToPx()
                    val itemSpacingPx = itemSpacing.roundToPx()
                    val itemMaxWidthPx = itemMaxWidth.roundToPx()

                    val columns = if (outerConstraints.maxWidth < multipleColumnsBreakPointPx) 1 else 2
                    val itemWidth = if (columns == 1) {
                        outerConstraints.maxWidth
                    } else {
                        val maxWidthWithSpaces = outerConstraints.maxWidth - (columns - 1) * itemSpacingPx
                        (maxWidthWithSpaces / columns).coerceIn(0, itemMaxWidthPx)
                    }
                    val itemConstraints = outerConstraints.copy(maxWidth = itemWidth)

                    val rowHeights = IntArray(measurables.size / columns + 1)
                    val placeables = measurables.mapIndexed { index, measureable ->
                        val placeable = measureable.measure(itemConstraints)
                        val row = index.floorDiv(columns)
                        rowHeights[row] = max(rowHeights[row], placeable.height)
                        placeable
                    }

                    val layoutHeight = topPaddingPx + rowHeights.sum()
                    val layoutWidth = itemWidth * columns + (itemSpacingPx * (columns - 1))

                    layout(
                        width = outerConstraints.constrainWidth(layoutWidth),
                        height = outerConstraints.constrainHeight(layoutHeight),
                    ) {
                        var yPosition = topPaddingPx
                        placeables.chunked(columns).forEachIndexed { rowIndex, row ->
                            var xPosition = 0
                            row.forEach { placeable ->
                                placeable.placeRelative(x = xPosition, y = yPosition)
                                xPosition += placeable.width + itemSpacingPx
                            }
                            yPosition += rowHeights[rowIndex]
                        }
                    }
                }
            }

            @Composable
            fun box() {
                InterestsAdaptiveContentLayout(topPadding = 8.dp) {
                    Text("Android")
                    Text("Compose")
                    Box(Modifier.size(20.dp))
                }
            }
        """.trimIndent()
        val service = previewSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", code)))))
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val diags = program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "the layout must lower cleanly; diags=$diags")
        val entry = program.getValue("box/0")
        var hard: String? = null
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entry, program, classes, emptyList(), onError = { hard = it.message }, onPartialError = { partials.add(it?.message) })
        }
        val threw = try {
            val scene = ImageComposeScene(300, 300, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            null
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return
            "${t.javaClass.simpleName}: ${t.message}"
        }
        assertTrue(threw == null, "the custom Layout must render; threw $threw; hard=$hard partials=${partials.filterNotNull()}")
        assertTrue(hard == null && partials.filterNotNull().isEmpty(), "no render errors; hard=$hard partials=${partials.filterNotNull()}")
    }

    private class Disk(val p: java.nio.file.Path) : VirtualFile {
        override val path get() = p.toString(); override val name get() = p.fileName?.toString() ?: p.toString()
        override val isDirectory get() = java.nio.file.Files.isDirectory(p); override val exists get() = java.nio.file.Files.exists(p)
        override val length get() = if (exists && !isDirectory) java.nio.file.Files.size(p) else 0L
        override fun parent(): VirtualFile? = p.parent?.let { Disk(it) }
        override fun children(): List<VirtualFile> = if (isDirectory) java.nio.file.Files.list(p).use { s -> s.map { Disk(it) as VirtualFile }.toList() } else emptyList()
        override fun contentHash() = ContentHash(if (exists && !isDirectory) java.nio.file.Files.readString(p) else path)
        override fun readBytes() = if (exists && !isDirectory) java.nio.file.Files.readAllBytes(p) else ByteArray(0)
        override fun readText(): CharSequence = if (exists && !isDirectory) java.nio.file.Files.readString(p) else ""
    }
    private class DiskDoc(val f: java.nio.file.Path) : DocumentSnapshot {
        override val text: CharSequence = java.nio.file.Files.readString(f); override val file: VirtualFile = Disk(f); override val version = 1L
        override fun length() = text.length
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
