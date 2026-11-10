package dev.ide.android.layouteditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.AndroidLayoutModel
import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutImage
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutRenderer
import dev.ide.android.layout.LayoutValueCompletion
import dev.ide.android.layout.LayoutViewNode
import dev.ide.platform.ServiceKey
import dev.ide.plugin.ui.EditorPreviewContext
import dev.ide.plugin.ui.TextReplacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the design editor against a fake renderer and model, to PNGs under
 * `<tmpdir>/codeassist-snapshots/layout-editor`, for looking at. The fake render paints each view's bounds in a
 * flat colour, so what is checked by eye is the chrome and how the overlays line up, not the layout itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesignerSnapshotTest {

    private val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/layout-editor").apply { mkdirs() }

    private val xml = """
        <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <ImageView android:id="@+id/iv_logo" android:layout_width="48dp" android:layout_height="48dp"/>
            <TextView android:id="@+id/tv_title" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Welcome back"/>
            <TextView android:id="@+id/tv_subtitle" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <com.google.android.material.textfield.TextInputLayout android:id="@+id/til_email" android:layout_width="0dp" android:layout_height="wrap_content">
                <EditText android:id="@+id/et_email" android:layout_width="match_parent" android:layout_height="wrap_content"/>
            </com.google.android.material.textfield.TextInputLayout>
            <com.google.android.material.textfield.TextInputLayout android:id="@+id/til_password" android:layout_width="0dp" android:layout_height="wrap_content">
                <EditText android:id="@+id/et_password" android:layout_width="match_parent" android:layout_height="wrap_content"/>
            </com.google.android.material.textfield.TextInputLayout>
            <Button
                android:id="@+id/btn_sign_in"
                android:layout_width="0dp"
                android:layout_height="48dp"
                android:text="@string/sign_in"/>
            <TextView android:id="@+id/tv_forgot" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
        </androidx.constraintlayout.widget.ConstraintLayout>
    """.trimIndent()

    /** A fake render: stacks the root's children down the screen and paints each a flat colour. */
    private class FakeRenderer : LayoutRenderer {
        override val progress: StateFlow<String?> = MutableStateFlow(null)
        override fun prepareLibraries(path: String) = false
        override suspend fun render(request: LayoutRenderRequest): LayoutRenderResult {
            val doc = parseTestLayout(request.text)
            val w = request.widthPx
            val h = request.heightPx
            val d = request.density
            val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
            fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) {
                for (y in t.coerceAtLeast(0) until b.coerceAtMost(h)) for (x in l.coerceAtLeast(0) until r.coerceAtMost(w)) px[y * w + x] = c
            }
            val colors = listOf(0xFFE0E7FF, 0xFFF3F4F6, 0xFFF3F4F6, 0xFFF9FAFB, 0xFFF9FAFB, 0xFF4F46E5, 0xFFEEF2FF).map { it.toInt() }
            var y = (96 * d).toInt()
            val children = doc.root!!.children.mapIndexed { i, el ->
                val hh = (if (el.tag == "Button") 48 else 56) * d
                val l = (24 * d).toInt()
                val node = LayoutViewNode(el.tag, el.id, l, y, w - l, y + hh.toInt(), el.start,
                    children = el.children.map { c -> LayoutViewNode(c.tag, c.id, l + 8, y + 8, w - l - 8, y + hh.toInt() - 8, c.start) })
                fill(node.left, node.top, node.right, node.bottom, colors[i % colors.size])
                y += hh.toInt() + (16 * d).toInt()
                node
            }
            val root = LayoutViewNode(doc.root!!.tag, null, 0, 0, w, h, 0, children = children)
            return LayoutRenderResult(LayoutImage(w, h, argb = px), LayoutViewNode("DecorView", null, 0, 0, w, h, null, children = listOf(root)))
        }
    }

    private class FakeModel : AndroidLayoutModel {
        override fun parse(text: String): LayoutDocument = parseTestLayout(text)
        override suspend fun attributes(path: String, tag: String, parentTag: String?) = listOf(
            LayoutAttributeInfo("android:id", setOf("reference"), resourceTypes = listOf("id")),
            LayoutAttributeInfo("android:layout_width", setOf("dimension", "enum"), listOf("match_parent", "wrap_content"), resourceTypes = listOf("dimen")),
            LayoutAttributeInfo("android:layout_height", setOf("dimension", "enum"), listOf("match_parent", "wrap_content"), resourceTypes = listOf("dimen")),
            LayoutAttributeInfo("android:text", setOf("string"), resourceTypes = listOf("string")),
            LayoutAttributeInfo("android:enabled", setOf("boolean"), resourceTypes = listOf("bool")),
            LayoutAttributeInfo("app:backgroundTint", setOf("color", "reference"), resourceTypes = listOf("color")),
            LayoutAttributeInfo("android:visibility", setOf("enum"), listOf("visible", "invisible", "gone")),
            LayoutAttributeInfo("android:padding", setOf("dimension"), resourceTypes = listOf("dimen")),
            LayoutAttributeInfo("android:layout_margin", setOf("dimension"), resourceTypes = listOf("dimen")),
            LayoutAttributeInfo("android:gravity", setOf("flags"), flagValues = listOf("top", "bottom", "start", "end", "center")),
        )
        override suspend fun completeValue(path: String, text: String, element: ElementRef, attribute: String, fieldText: String, caret: Int) =
            LayoutValueCompletion(emptyList(), 0, 0)
        override suspend fun setAttribute(path: String, text: String, element: ElementRef, attribute: String, value: String) = emptyList<LayoutEdit>()
        override suspend fun removeAttribute(path: String, text: String, element: ElementRef, attribute: String) = emptyList<LayoutEdit>()
        override suspend fun resources(path: String, type: String) = when (type) {
            "string" -> listOf(dev.ide.android.layout.LayoutResource("string", "sign_in", "@string/sign_in", value = "Sign in"))
            "color" -> listOf(0xFF6750A4, 0xFF625B71, 0xFFB3261E).mapIndexed { i, c -> dev.ide.android.layout.LayoutResource("color", "brand_$i", "@color/brand_$i", argb = c.toInt()) }
            else -> emptyList()
        }
        override suspend fun themeColors(path: String) = listOf("colorPrimary" to 0xFF006A6A, "colorSecondary" to 0xFF4A6363, "colorSurface" to 0xFFF4FBFA)
            .map { (n, c) -> dev.ide.android.layout.LayoutResource("attr", n, "?attr/$n", argb = c.toInt()) }
    }

    private inner class FakeContext(override val isSplit: Boolean, override val caretOffset: Int) : EditorPreviewContext {
        private val renderer = FakeRenderer()
        private val model = FakeModel()
        override val path = "/p/app/src/main/res/layout/activity_login.xml"
        override val text = xml
        override val dark = true
        override val canEdit = true
        override val projectPath = "/p"
        override val activeFilePath = path
        override fun reportProblems(problems: List<String>) {}
        override fun openFile(path: String, offset: Int) {}
        override fun openScreen(id: String) {}
        override fun applyEdits(edits: List<TextReplacement>) {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> service(key: ServiceKey<T>): T? = when (key.id) {
            LAYOUT_RENDERER.id -> renderer as T
            ANDROID_LAYOUT_MODEL.id -> model as T
            else -> null
        }
    }

    private fun snap(name: String, wDp: Int, hDp: Int, dark: Boolean, ctx: EditorPreviewContext, taps: List<Offset> = emptyList()): File {
        val density = 2f
        val scene = ImageComposeScene(wDp * 2, hDp * 2, Density(density)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF80D5D4), primaryContainer = Color(0xFF00504F), secondaryContainer = Color(0xFF324B4B)) else lightColorScheme()) {
                LayoutDesigner(ctx)
            }
        }
        var t = 0L
        repeat(40) { scene.render(t); t += 16_000_000L; Thread.sleep(10) }
        for (p in taps) {
            val at = Offset(p.x * density, p.y * density)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            repeat(30) { scene.render(t); t += 16_000_000L }
        }
        repeat(20) { scene.render(t); t += 16_000_000L; Thread.sleep(10) }
        val img = scene.render(t)
        val file = File(out, "$name.png")
        file.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        return file
    }

    @Test
    fun phoneWithASelection() {
        val caret = xml.indexOf("<Button")
        val f = snap("phone-selected", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = caret))
        assertTrue(f.length() > 0)
    }

    @Test
    fun phoneWithTheAttributesSheet() {
        val caret = xml.indexOf("<Button")
        // The Attributes button on the bottom toolbar: 16dp inset, 6dp padding, the second 48dp button.
        val f = snap("phone-attributes", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = caret), taps = listOf(Offset(98f, 892f - 46f)))
        assertTrue(f.length() > 0)
    }

    @Test
    fun phoneWithTheTreeSheet() {
        val caret = xml.indexOf("<Button")
        val f = snap("phone-tree", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = caret), taps = listOf(Offset(46f, 892f - 46f)))
        assertTrue(f.length() > 0)
    }

    @Test
    fun phoneStripWithTheSizeEditor() {
        val caret = xml.indexOf("<Button")
        // The strip's third tile (Enabled) is at 20 + 2 * 120 + 56; Size starts past the edge, so open Enabled's neighbour.
        val f = snap("phone-dialog-enabled", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = caret), taps = listOf(Offset(316f, 892f - 66f)))
        assertTrue(f.length() > 0)
    }

    @Test
    fun phoneStripWithTheColorEditor() {
        val caret = xml.indexOf("<Button")
        val f = snap("phone-dialog-color", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = caret), taps = listOf(Offset(196f, 892f - 66f)))
        assertTrue(f.length() > 0)
    }

    @Test
    fun phoneWithTheRootSelected() {
        // The root fills the screen: its action bar has no room above or below and sits at the visible bottom.
        val f = snap("phone-root", 412, 892, dark = true, FakeContext(isSplit = true, caretOffset = 5))
        assertTrue(f.length() > 0)
    }

    @Test
    fun wideDocked() {
        val caret = xml.indexOf("<Button")
        val f = snap("wide-docked", 1280, 800, dark = true, FakeContext(isSplit = true, caretOffset = caret))
        assertTrue(f.length() > 0)
    }

    @Test
    fun wideLightNothingSelected() {
        val f = snap("wide-light", 1280, 800, dark = false, FakeContext(isSplit = false, caretOffset = 0))
        assertTrue(f.length() > 0)
    }
}
