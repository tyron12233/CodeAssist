package dev.ide.preview.layoutlib

import dev.ide.core.IdeServices
import dev.ide.preview.PreviewRequest
import dev.ide.testkit.withTempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The IDE's real-view path with layoutlib answering it: the same call the layout designer's renderer makes on a
 * device, on a host with no Android framework. Nothing is built and nothing is linked first.
 */
class IdeRealViewThroughLayoutlibTest {

    @Test
    fun theSampleProjectsLayoutRendersWithRealViewsAndMapsBackToItsSource() {
        withTempDir("ide-layoutlib") { dir ->
            val runtime = LayoutlibRealViewRuntime(File(System.getProperty("layoutlib.home")), File(System.getProperty("render.out"), "cache"))
            IdeServices.bootstrapDemo(dir, realViewRuntime = runtime).use { ide ->
                val layout = ide.workspaceRoot.resolve("app/src/main/res/layout/activity_main.xml")
                val text = layout.readText()
                val result = assertNotNull(
                    ide.layoutPreview(layout, text, PreviewRequest(widthPx = 1080, heightPx = 1920, density = 3f, realViews = true)),
                )
                val argb = assertNotNull(result.renderedArgb, "a real-view picture came back; problems: ${result.problems.map { it.message }}")
                assertEquals(1080 to 1920, result.renderedArgbWidth to result.renderedArgbHeight)
                assertEquals(1080 * 1920, argb.size)
                val image = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, 1080, 1920, argb, 0, 1080)
                ImageIO.write(image, "png", File(System.getProperty("render.out"), "ide_sample_activity_main.png").apply { parentFile.mkdirs() })

                val root = assertNotNull(result.viewTree)
                fun all(n: dev.ide.preview.PreviewViewNode): List<dev.ide.preview.PreviewViewNode> = listOf(n) + n.children.flatMap(::all)
                val views = all(root)
                assertTrue(views.any { it.simpleName.contains("TextView") }, "the layout's TextView was inflated: ${views.map { it.className }}")
                // The host stamps each view with the offset of the tag it came from, which is what tap-to-edit needs.
                val mapped = views.filter { it.sourceOffset != null }
                assertTrue(mapped.isNotEmpty(), "views map back to the buffer")
                val textView = views.first { it.simpleName.contains("TextView") && it.sourceOffset != null }
                assertEquals('<', text[textView.sourceOffset!!])
                assertTrue(text.startsWith("<TextView", textView.sourceOffset!!) || text.substring(textView.sourceOffset!!).startsWith("<"), "the offset is a tag start")

                // An edit to the buffer that is not on disk yet is what gets rendered.
                val edited = text.replaceFirst("<TextView", "<TextView android:tag=\"edited\"")
                val again = assertNotNull(ide.layoutPreview(layout, edited, PreviewRequest(widthPx = 1080, heightPx = 1920, density = 3f, realViews = true)))
                assertNotNull(again.renderedArgb, "the unsaved buffer renders")
            }
        }
    }
}
