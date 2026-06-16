package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.PreviewEngine
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravityTest {

    private val ctx = SimpleRenderContext(HeadlessGraphics(), ProjectPreviewResources(ResourceRepository(emptyList()), density = 1f), density = 1f)
    private val engine = PreviewEngine(ctx)

    private fun layout(xml: String, w: Int = 400, h: Int = 400): dev.ide.preview.RenderNode {
        val root = LayoutInflater().inflate(xml, ctx)
        engine.render(root, w, h, RecordingCanvas())
        return root
    }

    @Test fun `frame layout_gravity centers a child`() {
        val root = layout(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:layout_width="100px" android:layout_height="40px"
                    android:layout_gravity="center" android:text="x"/>
            </FrameLayout>
            """.trimIndent(),
        )
        val child = root.children[0]
        assertEquals((400 - 100) / 2, child.left, "centered horizontally")
        assertEquals((400 - 40) / 2, child.top, "centered vertically")
    }

    @Test fun `frame layout_gravity bottom-end anchors a child`() {
        val root = layout(
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:layout_width="100px" android:layout_height="40px"
                    android:layout_gravity="bottom|end" android:text="x"/>
            </FrameLayout>
            """.trimIndent(),
        )
        val child = root.children[0]
        assertEquals(400 - 100, child.left)
        assertEquals(400 - 40, child.top)
    }

    @Test fun `vertical linear container gravity center offsets the whole run`() {
        val root = layout(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent"
                android:orientation="vertical" android:gravity="center_vertical">
                <TextView android:layout_width="100px" android:layout_height="50px" android:text="a"/>
                <TextView android:layout_width="100px" android:layout_height="50px" android:text="b"/>
            </LinearLayout>
            """.trimIndent(),
        )
        // Two 50px children (100px used) centered in 400px → first child top at (400-100)/2 = 150.
        assertEquals(150, root.children[0].top)
        assertEquals(200, root.children[1].top)
    }

    @Test fun `vertical linear cross-axis child layout_gravity end aligns right`() {
        val root = layout(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                <TextView android:layout_width="100px" android:layout_height="40px"
                    android:layout_gravity="end" android:text="a"/>
            </LinearLayout>
            """.trimIndent(),
        )
        assertEquals(400 - 100, root.children[0].left, "child pinned to the right edge")
    }
}
