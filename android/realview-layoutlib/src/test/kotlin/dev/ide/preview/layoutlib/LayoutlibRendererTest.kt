package dev.ide.preview.layoutlib

import dev.ide.preview.PreviewViewNode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layouts rendered by the real Android view classes on this JVM. The pictures are written to
 * `build/renders` for looking at; what is asserted is the captured tree, which is what the Preview's
 * inspector and tap-to-edit read.
 */
class LayoutlibRendererTest {

    private val fixtureRes = File(checkNotNull(javaClass.getResource("/fixture/res")).toURI())
    private val libraries = File(System.getProperty("fixture.libraries"))
    private val cacheDir = File(System.getProperty("render.out"), "cache")

    private fun frameworkRequest(night: Boolean = false) = LayoutlibRequest(
        layoutFile = File(fixtureRes, "layout/framework_widgets.xml"),
        resourceDirs = listOf(fixtureRes),
        classpath = emptyList(),
        resourcePackages = listOf("com.example.app"),
        themeName = if (night) "android:Theme.Material.NoActionBar" else "android:Theme.Material.Light.NoActionBar",
        widthPx = 1080, heightPx = 1920, density = 3f, night = night, cacheDir = cacheDir,
    )

    private fun materialRequest(): LayoutlibRequest {
        val aars = File(libraries, "aar").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        val jars = File(libraries, "jars").listFiles { f -> f.name.endsWith(".jar") && !f.name.startsWith("kotlin-stdlib") }.orEmpty()
        val packages = aars.mapNotNull { aar ->
            Regex("package=\"([^\"]+)\"").find(File(aar, "AndroidManifest.xml").readText())?.groupValues?.get(1)
        }
        return LayoutlibRequest(
            layoutFile = File(fixtureRes, "layout/material_components.xml"),
            resourceDirs = aars.map { File(it, "res") }.filter { it.isDirectory } + fixtureRes,
            classpath = aars.map { File(it, "classes.jar") }.filter { it.isFile } + jars,
            resourcePackages = packages + "com.example.app",
            themeName = "Theme.Material3.Light.NoActionBar",
            widthPx = 1080, heightPx = 1920, density = 3f, cacheDir = cacheDir,
        )
    }

    private fun save(result: LayoutlibResult, name: String) {
        val pixels = result.pixels ?: return
        val image = BufferedImage(result.width, result.height, BufferedImage.TYPE_INT_ARGB_PRE)
        image.setRGB(0, 0, result.width, result.height, pixels, 0, result.width)
        val out = File(System.getProperty("render.out"), "$name.png")
        out.parentFile.mkdirs()
        ImageIO.write(image, "png", out)
    }

    private fun PreviewViewNode.all(): List<PreviewViewNode> = listOf(this) + children.flatMap { it.all() }
    private fun PreviewViewNode.find(simpleName: String) =
        all().firstOrNull { it.simpleName == simpleName } ?: error("no $simpleName among ${all().map { it.className }}")

    @Test
    fun frameworkWidgetsAreMeasuredLaidOutAndDrawn() {
        val result = renderer.render(frameworkRequest())
        assertNull(result.error, "render failed; problems: ${result.problems}")
        save(result, "framework_widgets")
        assertEquals(1080 to 1920, result.width to result.height)
        assertEquals(1080 * 1920, checkNotNull(result.pixels).size)
        assertTrue(result.pixels!!.toSet().size > 100, "the picture is not one flat colour")

        val root = assertNotNull(result.viewTree)
        // A view layoutlib could not construct is replaced by a grey MockView box, and the reason is a problem.
        assertTrue(root.all().none { it.simpleName == "MockView" }, "every view was constructed; problems: ${result.problems}")
        assertEquals("android.widget.LinearLayout", root.className)
        assertEquals(listOf(0, 0, 1080, 1920), listOf(root.left, root.top, root.right, root.bottom))
        // 24dp of padding at density 3 puts the first child at 72,72: the bounds are in the picture's pixels.
        val title = root.children.first()
        assertEquals("android.widget.TextView", title.className)
        assertEquals(72 to 72, title.left to title.top)
        assertEquals("Hello from layoutlib", title.properties.first { it.name == "text" }.value, "@string/title resolved")
        // A nested view's bounds are absolute too, not relative to its row.
        val button = root.find("Button")
        val row = root.children.first { it.children.any { c -> c.simpleName == "Button" } }
        assertEquals(row.left to row.top, button.left to button.top)
        assertTrue(button.width > 0 && button.height > 0)
        for (name in listOf("EditText", "CheckBox", "Switch", "SeekBar", "ProgressBar", "RatingBar")) {
            val view = root.find(name)
            assertTrue(view.width > 0 && view.height > 0, "$name has a size: ${view.width}x${view.height}")
        }
        assertEquals(root.find("CheckBox"), root.hitTest(root.find("CheckBox").left + 5f, root.find("CheckBox").top + 5f))
    }

    @Test
    fun theSameRequestRendersTheSamePictureFromTheCache() {
        val first = renderer.render(frameworkRequest())
        val kept = cacheDir.list().orEmpty().toList()
        assertTrue(kept.any { it.startsWith("framework-port-notnight-xxhdpi-") }, "the framework table was kept: $kept")
        assertTrue(kept.any { it.startsWith("project-") }, "the project table was kept: $kept")
        val second = renderer.render(frameworkRequest())
        assertContentEquals(first.pixels, second.pixels)
    }

    @Test
    fun nightModeResolvesTheNightTheme() {
        val day = renderer.render(frameworkRequest())
        val night = renderer.render(frameworkRequest(night = true))
        assertNull(night.error, "render failed; problems: ${night.problems}")
        save(night, "framework_widgets_night")
        assertTrue(!day.pixels.contentEquals(night.pixels), "the dark theme draws differently")
    }

    @Test
    fun libraryViewsLoadFromTheirJarsWithGeneratedIds() {
        val result = renderer.render(materialRequest())
        assertNull(result.error, "render failed; problems: ${result.problems}")
        save(result, "material_components")
        val root = assertNotNull(result.viewTree)
        val names = root.all().map { it.simpleName }.toSet()
        for (expected in listOf("MaterialTextView", "TextInputLayout", "TextInputEditText", "MaterialButton", "MaterialSwitch",
            "ChipGroup", "Chip", "Slider", "MaterialCardView", "LinearProgressIndicator", "ExtendedFloatingActionButton")) {
            assertTrue(expected in names, "$expected was inflated; got $names")
        }
        val button = root.find("MaterialButton")
        assertTrue(button.width > 100 && button.height > 60, "a themed button has a real size: ${button.width}x${button.height}")
        assertEquals("Filled", button.properties.first { it.name == "text" }.value)
        // The filled button is drawn in the theme's primary colour, which only a resolved Material theme supplies.
        val centre = result.pixels!![(button.top + 20) * result.width + button.left + 40]
        val red = (centre shr 16) and 0xff
        val blue = centre and 0xff
        assertTrue(blue > red && (centre ushr 24) > 200, "the button is filled with the purple primary: ${Integer.toHexString(centre)}")
    }

    @Test
    fun theFlatEntryAnswersTheSamePictureAndTree() {
        // What the iOS host does: the request as text in, the result as bytes out, nothing else shared.
        val home = File(System.getProperty("layoutlib.home"))
        val direct = renderer.render(frameworkRequest())
        val bytes = LayoutlibEntry.render(LayoutlibWire.request(home, frameworkRequest()))
        val decoded = LayoutlibWire.decodeResult(bytes)
        assertNull(decoded.error)
        assertEquals(direct.width to direct.height, decoded.width to decoded.height)
        assertContentEquals(direct.pixels, decoded.pixels)
        fun shape(n: PreviewViewNode): String =
            "${n.className}#${n.id}[${n.left},${n.top},${n.right},${n.bottom}]${n.properties.map { it.name + "=" + it.value }}(${n.children.joinToString(",") { shape(it) }})"
        assertEquals(shape(direct.viewTree!!), shape(decoded.viewTree!!))
        // Little-endian ARGB ints are BGRA bytes: the first pixel's alpha is the fourth byte after the description.
        val first = direct.pixels!![0]
        val offset = bytes.size - direct.width * direct.height * 4
        assertEquals((first ushr 24).toByte(), bytes[offset + 3])
        assertEquals((first and 0xff).toByte(), bytes[offset])

        val failed = LayoutlibWire.decodeResult(LayoutlibEntry.render("home=${home.path}\nlayout=/nowhere.xml\n"))
        assertNull(failed.pixels)
        assertTrue(failed.error!!.contains("theme"), "a request with a field missing says which: ${failed.error}")
    }

    @Test
    fun aMissingThemeIsReportedNotThrown() {
        val request = frameworkRequest().let {
            LayoutlibRequest(it.layoutFile, it.resourceDirs, it.classpath, it.resourcePackages, "Theme.DoesNotExist",
                it.widthPx, it.heightPx, it.density, cacheDir = it.cacheDir)
        }
        val result = renderer.render(request)
        assertNull(result.pixels)
        assertTrue(result.error!!.contains("Theme.DoesNotExist"), result.error)
    }

    private companion object {
        /** layoutlib starts once per process, so every test renders through the same one. */
        val renderer: LayoutlibRenderer by lazy { LayoutlibRenderer(LayoutlibHome(File(System.getProperty("layoutlib.home")))) }
    }
}
