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
                    // The project's own view is Java nobody built: the engine compiles it for the preview.
                    assertTrue(rendered.problems.none { "MyChart" in it.message || it.tag?.contains("MyChart") == true }, "problems: ${rendered.problems.map { "${it.tag} ${it.message}" }}")
                    assertTrue(assertNotNull(rendered.root).walk().any { it.className == "com.example.app.MyChart" }, "the project's view is in the tree as itself")
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

/** The project tree across the same channel: the engine's curated view, not the folder as it lies on disk. */
class EmbeddedFileTreeTest {
    @Test
    fun theTreeIsTheEnginesOwn() {
        withTempDir("ide-embedded-tree") { dir ->
            IdeServices.bootstrapDemo(dir).close()
            val mode = dev.ide.ui.backend.TreeViewMode.Project
            val request = dev.ide.ui.backend.remote.RemoteFileTree.request(mode)
            assertFailsWith<IllegalStateException> { dev.ide.ui.backend.remote.RemoteFileTree.read(EmbeddedLayoutHost.call(request)) }
            assertEquals("", EmbeddedLayoutHost.open(dir.toString(), System.getProperty("layoutlib.home"), File(System.getProperty("render.out"), "cache").path))
            try {
                val tree = dev.ide.ui.backend.remote.RemoteFileTree.read(EmbeddedLayoutHost.call(request))
                val modules = tree.children.filter { it.kind == dev.ide.ui.backend.NodeKind.Module }
                assertEquals(listOf("app", "core", "feature"), modules.map { it.name })
                val app = modules.first()
                assertEquals("app", app.moduleConfigName)
                fun dev.ide.ui.backend.TreeNode.all(): List<dev.ide.ui.backend.TreeNode> = listOf(this) + children.flatMap { it.all() }
                assertTrue(app.all().any { it.kind == dev.ide.ui.backend.NodeKind.Package && it.name == "com.example.app" }, "packages are one row: ${app.all().map { it.name }}")
                assertTrue(tree.all().none { it.name == ".platform" || it.name == "workspace.json" }, "the engine's files are not project content")
            } finally {
                EmbeddedLayoutHost.close()
            }
        }
    }
}

/** Build across the channel: the Run picker's tasks, and a build whose transcript arrives as it grows. */
class EmbeddedBuildTest {
    @Test
    fun aBuildIsStartedAndFollowedAcrossTheChannel() {
        withTempDir("ide-embedded-build") { dir ->
            IdeServices.bootstrapDemo(dir).close()
            val client = dev.ide.ui.backend.remote.RemoteBuild.Client { EmbeddedLayoutHost.call(it) }
            assertFailsWith<IllegalStateException> { client.tasks() }
            assertEquals("", EmbeddedLayoutHost.open(dir.toString(), System.getProperty("layoutlib.home"), File(System.getProperty("render.out"), "cache").path))
            try {
                val tasks = client.tasks().map { it.id }
                assertTrue("build:core" in tasks, "$tasks")
                client.runTask("build:core")
                var state = client.state()
                val deadline = System.currentTimeMillis() + 120_000
                var sawRunning = false
                while (System.currentTimeMillis() < deadline) {
                    state = client.state()
                    val running = state.status == dev.ide.ui.backend.RunStatus.Running
                    sawRunning = sawRunning || running
                    if (!running && (sawRunning || state.status != dev.ide.ui.backend.RunStatus.Idle)) break
                    Thread.sleep(50)
                }
                assertEquals(dev.ide.ui.backend.RunStatus.Succeeded, state.status, state.log.joinToString("\n") { it.message })
                assertTrue(state.log.any { "compileJava" in it.message }, "the transcript came across: ${state.log.map { it.message }}")
                assertEquals(state.log.size, client.state().log.size, "asking again adds nothing")
            } finally {
                EmbeddedLayoutHost.close()
            }
        }
    }
}
