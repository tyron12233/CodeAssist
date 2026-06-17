package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.preview.PreviewEngine
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderLoopTest {

    private val repo = ResourceRepository(listOf(
        ResourceItem(ResourceType.COLOR, "primary", value = "#FF6200EE"),
        ResourceItem(ResourceType.STRING, "greeting", value = "Hello"),
    ))
    private val ctx = SimpleRenderContext(HeadlessGraphics(), ProjectPreviewResources(repo, density = 1f), density = 1f)
    private val engine = PreviewEngine(ctx)

    private fun render(xml: String, w: Int = 400, h: Int = 800): Pair<dev.ide.preview.RenderNode, RecordingCanvas> {
        val root = LayoutInflater().inflate(xml, ctx)
        val canvas = RecordingCanvas()
        engine.render(root, w, h, canvas)
        return root to canvas
    }

    @Test fun `vertical linear layout stacks children top to bottom`() {
        val (root, canvas) = render(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Hello"/>
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="World"/>
            </LinearLayout>
            """.trimIndent(),
        )
        assertEquals(listOf("Hello", "World"), canvas.texts())
        assertEquals(400, root.width) // match_parent against the 400px snapshot width
        val hello = canvas.ops.first { it.text == "Hello" }
        val world = canvas.ops.first { it.text == "World" }
        assertTrue(world.t > hello.t, "second line should be below the first")
    }

    @Test fun `horizontal linear layout places children left to right`() {
        val (_, canvas) = render(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:orientation="horizontal">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="A"/>
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="BB"/>
            </LinearLayout>
            """.trimIndent(),
        )
        val a = canvas.ops.first { it.text == "A" }
        val bb = canvas.ops.first { it.text == "BB" }
        assertTrue(bb.l > a.l, "second child should be to the right")
    }

    @Test fun `background colour resolved from resources is painted`() {
        val (_, canvas) = render(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent"
                android:background="@color/primary"/>
            """.trimIndent(),
        )
        assertTrue(canvas.ops.any { it.kind == "rect" && it.color == 0xFF6200EE.toInt() }, "background rect should use the resolved colour")
    }

    @Test fun `unknown custom view falls back to a labelled placeholder, never crashing the tree`() {
        val (_, canvas) = render(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                <com.example.app.MyChart android:layout_width="match_parent" android:layout_height="200dp"/>
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="after"/>
            </LinearLayout>
            """.trimIndent(),
        )
        // The placeholder labels itself with the simple class name, and the sibling still renders.
        assertTrue(canvas.texts().contains("MyChart"))
        assertTrue(canvas.texts().contains("after"))
    }

    @Test fun `padding offsets text within a frame`() {
        val (_, canvas) = render(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content" android:layout_height="wrap_content" android:padding="10px">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Hi"/>
            </FrameLayout>
            """.trimIndent(),
        )
        val hi = canvas.ops.first { it.text == "Hi" }
        assertEquals(10f, hi.l, "text should be inset by the parent padding")
    }
}
