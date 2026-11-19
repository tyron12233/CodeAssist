package dev.ide.embedded

import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.android.layout.remote.RemoteLayoutException
import dev.ide.android.layout.remote.RemoteLayoutServices
import dev.ide.core.IdeServices
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the iOS app does, on this JVM: open a project in the embedded host, then drive the layout editor's two
 * services through nothing but the two static calls and the bytes between them.
 */
class EmbeddedLayoutHostTest {

    private val remote = RemoteLayoutServices { EmbeddedLayoutHost.call(it) }

    @Test
    fun theLayoutEditorsServicesAnswerFromTheRealBackendAcrossTheChannel() {
        withTempDir("ide-embedded") { dir ->
            runBlocking {
                assertEquals("No project is open in the embedded backend.",
                    assertFailsWith<RemoteLayoutException> { remote.model.attributes("x", "TextView", null) }.message)

                // A project on disk, as the iOS app has one in its Documents folder.
                IdeServices.bootstrapDemo(dir).close()
                val opened = EmbeddedLayoutHost.open(dir.toString(), System.getProperty("layoutlib.home"), File(System.getProperty("render.out"), "cache").path)
                assertEquals("", opened)
                try {
                    val layout = dir.resolve("app/src/main/res/layout/activity_main.xml")
                    val text = layout.readText()

                    val document = remote.model.parse(text)
                    val textView = assertNotNull(document.elements().firstOrNull { it.simpleName == "TextView" })
                    assertEquals("<TextView", text.substring(textView.start, textView.start + 9))

                    val rendered = remote.renderer.render(LayoutRenderRequest(layout.toString(), text, 1080, 1920, 3f))
                    val image = assertNotNull(rendered.image, "problems: ${rendered.problems.map { it.message }}")
                    val argb = assertNotNull(image.argb)
                    assertEquals(1080 * 1920, argb.size)
                    assertTrue(!rendered.approximate, "the picture is the real framework's, not the approximate renderer's")
                    val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    out.setRGB(0, 0, image.width, image.height, argb, 0, image.width)
                    ImageIO.write(out, "png", File(System.getProperty("render.out"), "embedded_activity_main.png").apply { parentFile.mkdirs() })
                    val drawn = assertNotNull(rendered.root).walk().first { it.simpleName.contains("TextView") && it.sourceOffset != null }
                    assertEquals(textView.start, drawn.sourceOffset, "the drawn view maps to the element the parser found")

                    val attributes = remote.model.attributes(layout.toString(), "TextView", "LinearLayout")
                    assertTrue(attributes.any { it.name == "android:text" } && attributes.any { it.name == "android:layout_width" }, "${attributes.size} attributes")
                    val gravity = attributes.first { it.name == "android:gravity" }
                    assertTrue("flags" in gravity.formats && "center" in gravity.flagValues)

                    val edits = remote.model.setAttribute(layout.toString(), text, ElementRef(textView.start, textView.id), "android:text", "Changed")
                    var edited = text
                    for (e in edits.sortedByDescending { it.start }) edited = edited.substring(0, e.start) + e.newText + edited.substring(e.end)
                    assertTrue("android:text=\"Changed\"" in edited, edited)

                    val completion = remote.model.completeValue(layout.toString(), text, ElementRef(textView.start, textView.id), "android:textColor", "@", 1)
                    assertTrue(completion.candidates.isNotEmpty(), "value completion offers references")
                    assertTrue(remote.model.viewClasses(layout.toString()).any { it.tag == "TextView" || it.tag.endsWith(".TextView") })
                } finally {
                    EmbeddedLayoutHost.close()
                }
            }
        }
    }
}
