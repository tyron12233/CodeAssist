package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.preview.PreviewEngine
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutFeaturesTest {

    private val ctx = SimpleRenderContext(HeadlessGraphics(), ProjectPreviewResources(ResourceRepository(emptyList()), density = 1f), density = 1f)
    private val engine = PreviewEngine(ctx)

    private fun render(inflater: LayoutInflater, xml: String, w: Int, h: Int): Pair<RenderNode, RecordingCanvas> {
        val root = inflater.inflate(xml, ctx)
        val canvas = RecordingCanvas()
        engine.render(root, w, h, canvas)
        return root to canvas
    }

    @Test fun `relative layout below and centerInParent`() {
        val (root, _) = render(
            LayoutInflater(),
            """
            <RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:id="@+id/a" android:layout_width="100px" android:layout_height="40px" android:layout_alignParentTop="true" android:text="a"/>
                <TextView android:id="@+id/b" android:layout_width="100px" android:layout_height="40px" android:layout_below="@id/a" android:text="b"/>
                <TextView android:layout_width="80px" android:layout_height="30px" android:layout_centerInParent="true" android:text="c"/>
            </RelativeLayout>
            """.trimIndent(),
            400, 400,
        )
        assertEquals(40, root.children[1].top, "b sits directly below a")
        assertEquals(160, root.children[2].left, "c centered horizontally")
        assertEquals(185, root.children[2].top, "c centered vertically")
    }

    @Test fun `scroll view measures its child unbounded along the scroll axis`() {
        val (root, _) = render(
            LayoutInflater(),
            """
            <ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                    <TextView android:layout_width="match_parent" android:layout_height="200px" android:text="1"/>
                    <TextView android:layout_width="match_parent" android:layout_height="200px" android:text="2"/>
                    <TextView android:layout_width="match_parent" android:layout_height="200px" android:text="3"/>
                </LinearLayout>
            </ScrollView>
            """.trimIndent(),
            400, 300,
        )
        assertEquals(300, root.height, "scroll viewport is bounded")
        assertEquals(600, root.children[0].height, "content keeps its full unbounded height")
    }

    @Test fun `include inflates the referenced layout`() {
        val inflater = LayoutInflater(layoutProvider = { name ->
            if (name == "toolbar") """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="56px" android:text="bar"/>""" else null
        })
        val (root, canvas) = render(
            inflater,
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                <include layout="@layout/toolbar"/>
            </LinearLayout>
            """.trimIndent(),
            400, 800,
        )
        assertEquals("TextView", root.children[0].tag, "include expanded to the referenced root")
        assertTrue(canvas.texts().contains("bar"))
    }

    @Test fun `text maxLines with ellipsize truncates to one line`() {
        val (_, canvas) = render(
            LayoutInflater(),
            """
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="100px" android:layout_height="wrap_content"
                android:maxLines="1" android:ellipsize="end"
                android:text="the quick brown fox jumps over the lazy dog"/>
            """.trimIndent(),
            100, 200,
        )
        assertEquals(1, canvas.texts().size, "truncated to a single line")
        assertTrue(canvas.texts()[0].endsWith("…"), "ellipsis appended")
    }

    @Test fun `inflater records render problems for unknown and custom views`() {
        val inflater = LayoutInflater()
        inflater.inflate(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                <FancyWidget android:layout_width="match_parent" android:layout_height="40px"/>
                <com.example.app.MyChart android:layout_width="match_parent" android:layout_height="40px"/>
            </LinearLayout>
            """.trimIndent(),
            ctx,
        )
        assertTrue(inflater.problems.any { it.tag == "FancyWidget" }, "unknown widget reported")
        assertTrue(inflater.problems.any { it.tag == "com.example.app.MyChart" }, "custom view reported")
    }

    @Test fun `drawable shape background paints its solid colour`() {
        val dir = Files.createTempDirectory("preview-bg")
        val shape = dir.resolve("bg.xml")
        shape.writeText(
            """
            <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
                <solid android:color="#FF112233"/>
                <corners android:radius="8dp"/>
            </shape>
            """.trimIndent(),
        )
        val repo = ResourceRepository(listOf(ResourceItem(ResourceType.DRAWABLE, "bg", source = shape)))
        val bgCtx = SimpleRenderContext(HeadlessGraphics(), ProjectPreviewResources(repo, density = 1f), density = 1f)
        val root = LayoutInflater().inflate(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent"
                android:background="@drawable/bg"/>
            """.trimIndent(),
            bgCtx,
        )
        val canvas = RecordingCanvas()
        PreviewEngine(bgCtx).render(root, 200, 200, canvas)
        assertTrue(canvas.ops.any { it.kind == "roundRect" && it.color == 0xFF112233.toInt() }, "shape solid fill painted: ${canvas.ops}")
    }
}
