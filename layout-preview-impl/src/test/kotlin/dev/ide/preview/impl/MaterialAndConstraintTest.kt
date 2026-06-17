package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.PreviewEngine
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialAndConstraintTest {

    private val ctx = SimpleRenderContext(HeadlessGraphics(), ProjectPreviewResources(ResourceRepository(emptyList()), density = 1f), density = 1f)
    private val engine = PreviewEngine(ctx)

    private fun layout(xml: String, w: Int, h: Int): Pair<RenderNode, RecordingCanvas> {
        val root = LayoutInflater().inflate(xml, ctx)
        val canvas = RecordingCanvas()
        engine.render(root, w, h, canvas)
        return root to canvas
    }

    @Test fun `material button renders a filled rounded label`() {
        val (root, canvas) = layout(
            """
            <com.google.android.material.button.MaterialButton
                xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="OK"/>
            """.trimIndent(),
            400, 200,
        )
        assertTrue(root.height >= 48, "button honours the 48dp min height")
        assertTrue(canvas.ops.any { it.kind == "roundRect" && it.color == 0xFF6750A4.toInt() }, "filled accent background")
        assertTrue(canvas.texts().contains("OK"))
    }

    @Test fun `constraint centering in parent is centered`() {
        val (root, _) = layout(
            """
            <androidx.constraintlayout.widget.ConstraintLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:layout_width="100px" android:layout_height="40px" android:text="c"
                    app:layout_constraintLeft_toLeftOf="parent" app:layout_constraintRight_toRightOf="parent"
                    app:layout_constraintTop_toTopOf="parent" app:layout_constraintBottom_toBottomOf="parent"/>
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
            400, 400,
        )
        val child = root.children[0]
        assertEquals((400 - 100) / 2, child.left, "centered horizontally")
        assertEquals((400 - 40) / 2, child.top, "centered vertically")
    }

    @Test fun `constraint 0dp width fills between opposing edges`() {
        val (root, _) = layout(
            """
            <androidx.constraintlayout.widget.ConstraintLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:layout_width="0dp" android:layout_height="40px" android:text="fill"
                    app:layout_constraintLeft_toLeftOf="parent" app:layout_constraintRight_toRightOf="parent"
                    app:layout_constraintTop_toTopOf="parent"/>
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
            400, 400,
        )
        assertEquals(400, root.children[0].width, "0dp width fills the parent span")
    }

    @Test fun `constraint toBottomOf stacks a sibling`() {
        val (root, _) = layout(
            """
            <androidx.constraintlayout.widget.ConstraintLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <TextView android:id="@+id/a" android:layout_width="100px" android:layout_height="50px" android:text="a"
                    app:layout_constraintTop_toTopOf="parent" app:layout_constraintLeft_toLeftOf="parent"/>
                <TextView android:id="@+id/b" android:layout_width="100px" android:layout_height="50px" android:text="b"
                    app:layout_constraintTop_toBottomOf="@id/a" app:layout_constraintLeft_toLeftOf="parent"/>
            </androidx.constraintlayout.widget.ConstraintLayout>
            """.trimIndent(),
            400, 400,
        )
        assertEquals(50, root.children[1].top, "b sits below a")
    }
}
