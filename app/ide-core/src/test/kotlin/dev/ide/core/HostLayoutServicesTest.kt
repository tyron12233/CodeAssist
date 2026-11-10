package dev.ide.core

import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.core.backend.parseLayoutDocument
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android layout services a plugin resolves (`dev.ide.android.layout`): the host renders a layout to an
 * image plus a view tree whose nodes map back to the buffer, and answers the layout model (parse, attribute
 * catalog, attribute edits) from the editor's own XML machinery. No real-view runtime is wired in tests, so the
 * render takes the owned path, which is exactly the one whose offsets this change added.
 */
class HostLayoutServicesTest {

    private val root = createTempDirectory("host-layout")
    private var services: IdeServices? = null
    private var backend: IdeServicesBackend? = null

    @AfterTest
    fun tearDown() {
        runCatching { backend?.close() }
        services?.close()
        root.toFile().deleteRecursively()
    }

    private val layoutText = """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:orientation="vertical"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <TextView
                android:id="@+id/title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hello" />
            <Button android:id="@+id/go" android:layout_width="match_parent" android:layout_height="48dp"/>
        </LinearLayout>
    """.trimIndent()

    @Test
    fun parseGivesElementsWithOffsetsAndAttributes() {
        val doc = parseLayoutDocument(layoutText)
        val rootEl = assertNotNull(doc.root)
        assertEquals("LinearLayout", rootEl.tag)
        assertEquals(0, rootEl.start)
        assertEquals(listOf("TextView", "Button"), rootEl.children.map { it.tag })
        val title = rootEl.children[0]
        assertEquals(layoutText.indexOf("<TextView"), title.start)
        assertEquals("title", title.id)
        assertEquals("Hello", title.attribute("android:text")?.value)
        // The start tag ends just past `/>` for a self-closing element.
        assertEquals(layoutText.indexOf("/>", title.start) + 2, title.startTagEnd)
        assertTrue(title.selfClosing)
        assertEquals(title, doc.find(ElementRef(12345, "title")))
        assertNull(parseLayoutDocument("").root)
    }

    @Test
    fun ownedRenderMapsNodesBackToTheBuffer() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        val b = IdeServicesBackend(initial = s).also { backend = it }
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        Files.writeString(layout, layoutText)

        val renderer = s.store.workspaceContainer.getService(LAYOUT_RENDERER)
        val result = runBlocking {
            renderer.render(LayoutRenderRequest(layout.toString(), layoutText, 720, 1280, 2f, showSystemUi = false))
        }
        assertTrue(result.approximate, "no real-view runtime in tests: the owned renderer answers")
        val tree = assertNotNull(result.root)
        val mapped = tree.walk().filter { it.sourceOffset != null }.toList()
        assertEquals(
            listOf(0, layoutText.indexOf("<TextView"), layoutText.indexOf("<Button")),
            mapped.map { it.sourceOffset },
        )
        val button = mapped.last()
        assertEquals("go", button.id)
        // Laid out even with no rasterizer: 48dp at density 2, full width.
        assertEquals(96, button.height)
        assertEquals(720, button.width)
        assertEquals(button, tree.hitTest(button.left + 1f, button.top + 1f))
        assertNotNull(b)
    }

    @Test
    fun modelOffersAttributesAndEdits() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        backend = IdeServicesBackend(initial = s)
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        Files.writeString(layout, layoutText)
        val model = s.store.workspaceContainer.getService(ANDROID_LAYOUT_MODEL)

        val attrs = runBlocking { model.attributes(layout.toString(), "TextView", "LinearLayout") }
        val byName = attrs.associateBy { it.name }
        assertNotNull(byName["android:text"], "a TextView takes text")
        val weight = assertNotNull(byName["android:layout_weight"], "a LinearLayout child takes layout_weight")
        assertTrue(weight.formats.isNotEmpty())
        val width = assertNotNull(byName["android:layout_width"])
        assertTrue("match_parent" in width.enumValues, "layout_width carries its keywords: ${width.enumValues}")

        val title = ElementRef(layoutText.indexOf("<TextView"), "title")
        val edited = apply(layoutText, runBlocking { model.setAttribute(layout.toString(), layoutText, title, "android:text", "Bye") })
        assertTrue("android:text=\"Bye\"" in edited, edited)
        val removed = apply(edited, runBlocking { model.removeAttribute(layout.toString(), edited, title, "android:text") })
        assertTrue("android:text" !in removed.substring(removed.indexOf("<TextView"), removed.indexOf("<Button")), removed)
    }

    @Test
    fun modelListsResourcesWithResolvedColors() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        backend = IdeServicesBackend(initial = s)
        val values = root.resolve("app/src/main/res/values")
        Files.createDirectories(values)
        Files.writeString(values.resolve("probe_colors.xml"), "<resources><color name=\"probe_red\">#FFFF0000</color></resources>")
        Files.writeString(values.resolve("probe_strings.xml"), "<resources><string name=\"probe_hi\">Hi there</string></resources>")
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        Files.writeString(layout, layoutText)
        val model = s.store.workspaceContainer.getService(ANDROID_LAYOUT_MODEL)

        val red = runBlocking { model.resources(layout.toString(), "color") }.firstOrNull { it.name == "probe_red" }
        assertNotNull(red)
        assertEquals("@color/probe_red", red.reference)
        assertEquals(0xFFFF0000.toInt(), red.argb)
        val hi = runBlocking { model.resources(layout.toString(), "string") }.firstOrNull { it.name == "probe_hi" }
        assertEquals("Hi there", hi?.value)
    }

    @Test
    fun modelListsViewClassesForThePalette() {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        backend = IdeServicesBackend(initial = s)
        val layout = root.resolve("app/src/main/res/layout/probe.xml")
        Files.createDirectories(layout.parent)
        Files.writeString(layout, layoutText)
        val model = s.store.workspaceContainer.getService(ANDROID_LAYOUT_MODEL)
        val classes = runBlocking { model.viewClasses(layout.toString()) }.associateBy { it.tag }
        val button = assertNotNull(classes["Button"], "framework widgets by simple name")
        assertEquals(dev.ide.android.layout.ViewOrigin.FRAMEWORK, button.origin)
        assertTrue(!button.isViewGroup)
        assertTrue(classes["LinearLayout"]?.isViewGroup == true)
    }

    private fun apply(text: String, edits: List<LayoutEdit>): String {
        var out = text
        for (e in edits.sortedByDescending { it.start }) out = out.substring(0, e.start) + e.newText + out.substring(e.end)
        return out
    }
}
