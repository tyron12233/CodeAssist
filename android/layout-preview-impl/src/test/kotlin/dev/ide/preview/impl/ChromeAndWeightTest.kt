package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.android.support.resources.StyleData
import dev.ide.preview.PreviewEngine
import dev.ide.preview.Props
import dev.ide.preview.RenderNode
import dev.ide.preview.SimpleRenderContext
import dev.ide.preview.impl.headless.HeadlessGraphics
import dev.ide.preview.impl.headless.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromeAndWeightTest {

    private val repo = ResourceRepository(
        items = listOf(ResourceItem(ResourceType.COLOR, "primary", value = "#FF6200EE")),
        styles = mapOf(
            "Theme.App" to StyleData(parent = "Theme.Material.Light", items = mapOf("android:colorPrimary" to "@color/primary")),
            "Theme.App.NoActionBar" to StyleData(parent = "Theme.App", items = emptyMap()),
        ),
    )
    private val res = ProjectPreviewResources(repo, density = 1f)
    private val ctx = SimpleRenderContext(HeadlessGraphics(), res, density = 1f)
    private val engine = PreviewEngine(ctx)

    @Test fun `chrome reads colorPrimary from the theme through its parent chain`() {
        val chrome = PreviewChrome.fromTheme(repo, res, "Theme.App", "Demo")
        assertEquals(0xFF6200EE.toInt(), chrome.actionBarColor)
        assertTrue(chrome.showActionBar)
    }

    @Test fun `NoActionBar theme hides the app bar`() {
        assertTrue(!PreviewChrome.fromTheme(repo, res, "Theme.App.NoActionBar", "Demo").showActionBar)
    }

    @Test fun `wrapped content renders status bar, app bar with title, and fills the rest`() {
        val content = RenderNode().apply {
            renderer = FrameLayoutRenderer
            props.layoutWidth = Props.MATCH_PARENT; props.layoutHeight = Props.MATCH_PARENT
        }
        val chrome = PreviewChrome.fromTheme(repo, res, "Theme.App", "Demo")
        val root = SystemChrome.wrap(content, chrome, ctx)
        val canvas = RecordingCanvas()
        engine.render(root, 400, 800, canvas)

        assertTrue(canvas.ops.any { it.kind == "rect" && it.color == chrome.statusBarColor && it.t == 0f && it.b == 24f }, "status bar")
        assertTrue(canvas.ops.any { it.kind == "rect" && it.color == chrome.actionBarColor && it.t == 24f && it.b == 80f }, "app bar")
        assertTrue(canvas.texts().contains("Demo"), "app bar title")
        assertEquals(720, content.height, "content fills below the 24+56 chrome bars")
    }

    @Test fun `layout_weight splits the main axis evenly`() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
                <TextView android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:text="A"/>
                <TextView android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:text="B"/>
            </LinearLayout>
        """.trimIndent()
        val root = LayoutInflater().inflate(xml, ctx)
        engine.render(root, 400, 800, RecordingCanvas())
        assertEquals(400, root.children[0].height)
        assertEquals(400, root.children[1].height)
    }
}
