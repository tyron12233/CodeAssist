package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.PreviewEngine
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test fun `coordinator scaffold renders a primary app bar at top and a surface bottom nav at the bottom`() {
        val (root, canvas) = layout(
            """
            <androidx.coordinatorlayout.widget.CoordinatorLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <com.google.android.material.appbar.AppBarLayout
                    android:layout_width="match_parent" android:layout_height="wrap_content">
                    <com.google.android.material.appbar.MaterialToolbar
                        android:id="@+id/toolbar"
                        android:layout_width="match_parent" android:layout_height="?attr/actionBarSize"/>
                </com.google.android.material.appbar.AppBarLayout>
                <androidx.constraintlayout.widget.ConstraintLayout
                    android:layout_width="match_parent" android:layout_height="match_parent"
                    app:layout_behavior="@string/appbar_scrolling_view_behavior">
                    <FrameLayout android:id="@+id/container"
                        android:layout_width="0dp" android:layout_height="0dp"
                        app:layout_constraintTop_toTopOf="parent"
                        app:layout_constraintBottom_toTopOf="@id/bottomNav"
                        app:layout_constraintStart_toStartOf="parent"
                        app:layout_constraintEnd_toEndOf="parent"/>
                    <com.google.android.material.bottomnavigation.BottomNavigationView
                        android:id="@+id/bottomNav"
                        android:layout_width="0dp" android:layout_height="wrap_content"
                        app:layout_constraintBottom_toBottomOf="parent"
                        app:layout_constraintStart_toStartOf="parent"
                        app:layout_constraintEnd_toEndOf="parent"/>
                </androidx.constraintlayout.widget.ConstraintLayout>
            </androidx.coordinatorlayout.widget.CoordinatorLayout>
            """.trimIndent(),
            400, 800,
        )
        assertEquals(800, root.height, "the coordinator fills the window")

        // The app bar: a full-width colorPrimary bar of actionBarSize height, pinned to the top.
        val primary = 0xFF6200EE.toInt()
        val appBar = assertNotNull(canvas.ops.firstOrNull { it.kind == "rect" && it.color == primary }, "a colorPrimary app bar is drawn")
        assertEquals(0f, appBar.l); assertEquals(400f, appBar.r)
        assertEquals(0f, appBar.t, "app bar at the very top")
        assertEquals(56f, appBar.b - appBar.t, "app bar honours actionBarSize (56dp @ density 1)")

        // The bottom nav: a full-width surface bar (56dp) flush with the bottom edge.
        val surface = 0xFFFFFFFF.toInt()
        val bottomNav = assertNotNull(canvas.ops.firstOrNull { it.kind == "rect" && it.color == surface }, "a colorSurface bottom-nav bar is drawn")
        assertEquals(800f, bottomNav.b, "bottom nav is flush with the bottom edge")
        assertEquals(56f, bottomNav.b - bottomNav.t, "bottom nav is 56dp tall")
        assertTrue(bottomNav.t > appBar.b, "the content/nav region sits below the app bar, not under it")
    }

    @Test fun `library list-container tags render via a registered renderer, not the custom-view placeholder`() {
        // A layout of only framework/library views (no user class). It must inflate cleanly — RecyclerView gets
        // a registered renderer, NOT a "custom view not rendered" placeholder (and upstream, no project compile).
        val inflater = LayoutInflater()
        val root = inflater.inflate(
            """
            <androidx.coordinatorlayout.widget.CoordinatorLayout
                xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <androidx.recyclerview.widget.RecyclerView
                    android:layout_width="match_parent" android:layout_height="match_parent"
                    app:layout_behavior="com.google.android.material.appbar.AppBarLayout${'$'}ScrollingViewBehavior"/>
            </androidx.coordinatorlayout.widget.CoordinatorLayout>
            """.trimIndent(),
            ctx,
        )
        assertTrue(inflater.problems.isEmpty(), "no custom-view problems for a library-only layout: ${inflater.problems}")
        assertEquals(CoordinatorLayoutRenderer, root.renderer)
        assertEquals(FrameLayoutRenderer, root.children.single().renderer, "RecyclerView uses a registered renderer")
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
