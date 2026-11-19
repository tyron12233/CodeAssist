package dev.ide.android.layout

import dev.ide.android.layout.remote.LayoutServiceDispatcher
import dev.ide.android.layout.remote.RemoteLayoutException
import dev.ide.android.layout.remote.RemoteLayoutServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two layout services reached across a byte channel answer what the services behind it answered: every
 * call, every field, with nothing shared between the two ends but the bytes.
 */
class RemoteLayoutServicesTest {

    private val seen = ArrayList<String>()

    private val realRenderer = object : LayoutRenderer {
        override val progress: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun render(request: LayoutRenderRequest): LayoutRenderResult {
            seen += "render ${request.path} ${request.widthPx}x${request.heightPx}@${request.density} night=${request.night} ui=${request.showSystemUi} scale=${request.scale} text=${request.text}"
            if (request.path == "boom") error("no such layout")
            val child = LayoutViewNode("android.widget.TextView", "title", 10, 20, 110, 60, sourceOffset = 42,
                properties = listOf(LayoutViewProperty("Text", "text", "Héllo\tworld\n")))
            return LayoutRenderResult(
                image = LayoutImage(2, 2, argb = intArrayOf(0x80112233.toInt(), -1, 0, 0x7fffffff)),
                root = LayoutViewNode("android.widget.LinearLayout", null, 0, 0, 200, 100, sourceOffset = null, children = listOf(child)),
                problems = listOf(LayoutProblem("com.example.MyChart", "ClassNotFoundException"), LayoutProblem(null, "untagged")),
                resourceErrors = listOf(LayoutResourceError("colors.xml", 7, "bad colour"), LayoutResourceError(null, null, "unknown")),
                librariesNeedPreparing = true, unpreparedLibraryCount = 3, approximate = false, widthPx = 200, heightPx = 100,
            )
        }
        override fun prepareLibraries(path: String): Boolean { seen += "prepare $path"; return path.endsWith("main.xml") }
    }

    private val realModel = object : AndroidLayoutModel {
        override fun parse(text: String): LayoutDocument {
            val leaf = LayoutElement("TextView", 12, 30, 28, true, listOf(LayoutAttribute("android:text", "Hi", 22, 27, 23, 26)), emptyList())
            return LayoutDocument(text, LayoutElement("LinearLayout", 0, text.length, 14, false, emptyList(), listOf(leaf)))
        }
        override suspend fun attributes(path: String, tag: String, parentTag: String?): List<LayoutAttributeInfo> {
            seen += "attributes $path $tag $parentTag"
            return listOf(LayoutAttributeInfo("android:gravity", setOf("flags"), flagValues = listOf("top", "center"), doc = "where"),
                LayoutAttributeInfo("android:textColor", setOf("color", "reference"), resourceTypes = listOf("color")))
        }
        override suspend fun completeValue(path: String, text: String, element: ElementRef, attribute: String, fieldText: String, caret: Int): LayoutValueCompletion {
            seen += "complete $path ${element.offset}/${element.id} $attribute '$fieldText' $caret"
            return LayoutValueCompletion(listOf(LayoutValueCandidate("@color/ink", "@color/ink", "#4A148C", "color"), LayoutValueCandidate("x", "y")), 1, 4)
        }
        override suspend fun setAttribute(path: String, text: String, element: ElementRef, attribute: String, value: String): List<LayoutEdit> {
            seen += "set $attribute=$value at ${element.offset}/${element.id}"
            return listOf(LayoutEdit(5, 5, " android:text=\"$value\""))
        }
        override suspend fun removeAttribute(path: String, text: String, element: ElementRef, attribute: String): List<LayoutEdit> =
            listOf(LayoutEdit(5, 22, ""))
        override suspend fun resources(path: String, type: String) =
            listOf(LayoutResource(type, "ink", "@$type/ink", "#FF4A148C", "/p/colors.xml", 0xFF4A148C.toInt(), library = true), LayoutResource(type, "bare", "@$type/bare"))
        override suspend fun themeColors(path: String) = if (path == "boom") error("no such layout") else listOf(LayoutResource("attr", "colorPrimary", "?attr/colorPrimary", argb = -1))
        override suspend fun viewClasses(path: String) =
            listOf(LayoutViewClass("android.widget.Button", false, ViewOrigin.FRAMEWORK, "A button"), LayoutViewClass("com.example.MyChart", true, ViewOrigin.PROJECT))
    }

    /** The channel: bytes in, bytes out, the dispatcher on the far side, as the JVM embedded in the iOS app is. */
    private val dispatcher = LayoutServiceDispatcher(realRenderer, realModel)
    private val remote = RemoteLayoutServices { request -> runBlocking { dispatcher.handle(request) } }

    @Test
    fun aRenderCrossesWithItsPictureTreeAndProblems() {
        runBlocking {
            val r = remote.renderer.render(LayoutRenderRequest("/p/res/layout/main.xml", "<a/>", 1080, 1920, 2.75f, night = true, showSystemUi = false, scale = 0.5f))
            assertEquals("render /p/res/layout/main.xml 1080x1920@2.75 night=true ui=false scale=0.5 text=<a/>", seen.single())
            assertEquals(2 to 2, r.image!!.width to r.image!!.height)
            assertContentEquals(intArrayOf(0x80112233.toInt(), -1, 0, 0x7fffffff), r.image!!.argb)
            assertNull(r.image!!.png)
            val root = r.root!!
            assertEquals(listOf(0, 0, 200, 100), listOf(root.left, root.top, root.right, root.bottom))
            assertNull(root.sourceOffset)
            val title = root.children.single()
            assertEquals("title" to 42, title.id to title.sourceOffset)
            assertEquals("Héllo\tworld\n", title.properties.single().value, "text survives as it is, tabs and accents included")
            assertEquals(listOf("com.example.MyChart" to "ClassNotFoundException", null to "untagged"), r.problems.map { it.tag to it.message })
            assertEquals(listOf(Triple("colors.xml", 7, "bad colour"), Triple(null, null, "unknown")), r.resourceErrors.map { Triple(it.file, it.line, it.message) })
            assertTrue(r.librariesNeedPreparing && r.unpreparedLibraryCount == 3 && !r.approximate)
            assertEquals(200 to 100, r.widthPx to r.heightPx)
        }
    }

    @Test
    fun theModelsCallsCrossWithEveryArgumentAndEveryField() {
        runBlocking {
            val doc = remote.model.parse("<LinearLayout><TextView android:text=\"Hi\"/></LinearLayout>")
            val leaf = doc.root!!.children.single()
            assertEquals("TextView", leaf.tag)
            assertEquals(doc.root, leaf.parent, "the tree is assembled, parents and all")
            assertEquals("Hi", leaf.attribute("android:text")!!.value)
            assertEquals(23 to 26, leaf.attributes.single().valueStart to leaf.attributes.single().valueEnd)

            val attrs = remote.model.attributes("/p/main.xml", "TextView", null)
            assertEquals("attributes /p/main.xml TextView null", seen.last())
            assertEquals(setOf("flags"), attrs[0].formats)
            assertEquals(listOf("top", "center") to "where", attrs[0].flagValues to attrs[0].doc)
            assertEquals(listOf("color"), attrs[1].resourceTypes)

            val completion = remote.model.completeValue("/p/main.xml", "<a/>", ElementRef(12, "title"), "android:textColor", "@co", 3)
            assertEquals("complete /p/main.xml 12/title android:textColor '@co' 3", seen.last())
            assertEquals(listOf("@color/ink" to "color", "x" to null), completion.candidates.map { it.label to it.kind })
            assertEquals(1 to 4, completion.replaceStart to completion.replaceEnd)

            val edits = remote.model.setAttribute("/p/main.xml", "<a/>", ElementRef(0), "android:text", "Bye \"now\"")
            assertEquals("set android:text=Bye \"now\" at 0/null", seen.last())
            assertEquals(LayoutEdit(5, 5, " android:text=\"Bye \"now\"\""), edits.single())
            assertEquals(LayoutEdit(5, 22, ""), remote.model.removeAttribute("/p/main.xml", "<a/>", ElementRef(0), "android:text").single())

            val colors = remote.model.resources("/p/main.xml", "color")
            assertEquals(listOf("@color/ink", "@color/bare"), colors.map { it.reference })
            assertTrue(colors[0].library && colors[0].argb == 0xFF4A148C.toInt() && colors[1].argb == null && colors[1].file == null)
            assertEquals(-1, remote.model.themeColors("/p/main.xml").single().argb)
            val classes = remote.model.viewClasses("/p/main.xml")
            assertEquals(listOf(ViewOrigin.FRAMEWORK, ViewOrigin.PROJECT), classes.map { it.origin })
            assertTrue(classes[1].isViewGroup && classes[1].doc == null)
            assertTrue(remote.renderer.prepareLibraries("/p/res/layout/main.xml"))
        }
    }

    @Test
    fun aRenderThatFailsOnTheFarSideSaysWhyInsteadOfThrowing() {
        runBlocking {
            // The editor waits for a result; a thrown render left it waiting for good.
            val result = remote.renderer.render(LayoutRenderRequest("boom", "", 1, 1, 1f))
            assertNull(result.image)
            assertEquals(listOf("no such layout"), result.problems.map { it.message })
            assertNull(remote.renderer.progress.value, "the stage is cleared after a failed render too")
        }
    }

    @Test
    fun aFailureOfAnyOtherCallArrivesAsItsMessage() {
        runBlocking {
            val failure = assertFailsWith<RemoteLayoutException> { remote.model.themeColors("boom") }
            assertEquals("no such layout", failure.message)
        }
    }
}
