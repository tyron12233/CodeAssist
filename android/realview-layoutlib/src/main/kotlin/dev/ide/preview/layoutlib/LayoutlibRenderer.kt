package dev.ide.preview.layoutlib

import com.android.ide.common.rendering.api.ActionBarCallback
import com.android.ide.common.rendering.api.AdapterBinding
import com.android.ide.common.rendering.api.AssetRepository
import com.android.ide.common.rendering.api.HardwareConfig
import com.android.ide.common.rendering.api.ILayoutLog
import com.android.ide.common.rendering.api.ILayoutPullParser
import com.android.ide.common.rendering.api.LayoutlibCallback
import com.android.ide.common.rendering.api.RenderSession
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.ViewInfo
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.layoutlib.bridge.Bridge
import com.android.layoutlib.bridge.android.RenderParamsFlags
import com.android.resources.Density
import com.android.resources.ResourceType
import com.android.resources.ScreenOrientation
import com.android.resources.ScreenRound
import com.android.resources.ScreenSize
import dev.ide.preview.PreviewViewNode
import dev.ide.preview.PreviewViewProperty
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Renders layouts with layoutlib: the Android framework's own view classes, measured, laid out and drawn on
 * this JVM.
 *
 * One per process. layoutlib starts a native runtime when it is first used and cannot start a second, so the
 * [Bridge] is made once and every render goes through it one at a time. What a render needs beyond the request
 * is kept between renders: the framework's resources, and per project its resources, ids and classes, which
 * are what the first render of a project spends its time on.
 */
class LayoutlibRenderer(private val home: LayoutlibHome) {

    private val problems = ArrayList<String>()
    private val bridge: Bridge by lazy { startBridge() }
    private val apiLevel: Int by lazy { properties()["ro.build.version.sdk"]?.toIntOrNull() ?: 36 }

    /** What stays the same from one render of a project to the next, keyed by everything it was built from. */
    private class Project(val key: Any, val resolverTables: Map<ResourceNamespace, ResourceTable>, val ids: ResourceIds, val classes: LibraryClassLoader)

    private var project: Project? = null
    private val frameworkTables = HashMap<String, ResourceTable>()

    @Synchronized
    fun render(request: LayoutlibRequest): LayoutlibResult {
        problems.clear()
        return try {
            renderLocked(request)
        } catch (t: Throwable) {
            LayoutlibResult(null, problems = problems.toList(), error = t.message ?: t.javaClass.name)
        }
    }

    private fun renderLocked(request: LayoutlibRequest): LayoutlibResult {
        val bridge = bridge
        val density = Density.create((request.density * 160).roundToInt())
        val portrait = request.heightPx >= request.widthPx
        val configText = listOfNotNull(
            "ldrtl".takeIf { request.rtl },
            if (portrait) "port" else "land",
            if (request.night) "night" else "notnight",
            density.resourceValue,
            "v$apiLevel",
        ).joinToString("-")
        val config = FolderConfiguration.getConfigForQualifierString(configText)
            ?: return LayoutlibResult(null, error = "configuration '$configText' is not one the resource system knows")

        val project = projectFor(request, config, configText)
        val themeName = request.themeName.removePrefix("@style/").removePrefix("@android:style/")
        val appStyles = project.resolverTables[ResourceNamespace.RES_AUTO]?.get(ResourceType.STYLE)
        // A manifest names a framework theme without a prefix as readily as one of its own.
        val theme = when {
            themeName.startsWith("android:") -> ResourceReference.style(ResourceNamespace.ANDROID, themeName.removePrefix("android:"))
            request.themeName.startsWith("@android:style/") || appStyles?.get(themeName) == null &&
                project.resolverTables[ResourceNamespace.ANDROID]?.get(ResourceType.STYLE)?.get(themeName) != null ->
                ResourceReference.style(ResourceNamespace.ANDROID, themeName)
            else -> ResourceReference.style(ResourceNamespace.RES_AUTO, themeName)
        }
        val resolver = ResourceResolver.create(project.resolverTables, theme)
        if (resolver.defaultTheme == null) return LayoutlibResult(null, error = "theme '${request.themeName}' is not in the resources")

        val hardware = HardwareConfig(
            request.widthPx, request.heightPx, density, density.dpiValue.toFloat(), density.dpiValue.toFloat(),
            ScreenSize.NORMAL, if (portrait) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE, ScreenRound.NOTROUND, false,
        )
        val params = SessionParams(
            FileParser(request.layoutFile, ResourceNamespace.RES_AUTO), SessionParams.RenderingMode.NORMAL, request.layoutFile.path,
            hardware, resolver, Callback(project), request.minSdk, apiLevel, log,
        )
        params.setForceNoDecor()
        params.setFlag(RenderParamsFlags.FLAG_DO_NOT_RENDER_ON_CREATE, true)
        params.setAssetRepository(Assets)
        if (request.rtl) params.setRtlSupport(true)

        val session = bridge.createSession(params)
        try {
            val inflated = session.result
            if (!inflated.isSuccess) {
                return LayoutlibResult(null, problems = problems.toList(), error = describe(inflated.errorMessage, inflated.exception))
            }
            var drawn = session.render()
            // Later frames: what was posted to the frame clock and the main looper runs, then the views are
            // measured and drawn again. Time moves a display frame at a time, as it would on a device.
            var frameTime = 0L
            for (frame in 1 until request.frames) {
                if (!drawn.isSuccess) break
                frameTime += FRAME_NANOS
                session.setElapsedFrameTimeNanos(frameTime)
                session.executeCallbacks(frameTime)
                drawn = session.render()
            }
            if (!drawn.isSuccess) {
                return LayoutlibResult(null, problems = problems.toList(), error = describe(drawn.errorMessage, drawn.exception))
            }
            val (pixels, width, height) = pixelsOf(session) ?: return LayoutlibResult(null, problems = problems.toList(), error = "layoutlib drew no image")
            val root = session.rootViews.firstOrNull()?.let { capture(it, 0, 0, project) }
            return LayoutlibResult(pixels, width, height, root, problems.toList())
        } finally {
            session.dispose()
        }
    }

    private fun describe(message: String?, cause: Throwable?): String {
        var deepest = cause
        while (deepest?.cause != null && deepest.cause !== deepest) deepest = deepest.cause
        return listOfNotNull(message, deepest?.toString()).distinct().joinToString(": ").ifEmpty { "render failed" }
    }

    // ---- start-up ------------------------------------------------------------------------------------------

    private fun properties(): Map<String, String> {
        val props = Properties()
        FileInputStream(home.buildProp).use { props.load(it) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    private fun startBridge(): Bridge {
        val bridge = Bridge()
        val started = bridge.init(
            properties(), home.fonts, home.nativeDir.path + File.separator, home.icuData.path, home.hyphenData.path,
            home.keyboards.map { it.path }.toTypedArray(), enumValues(File(home.frameworkRes(), "values/attrs.xml")), log,
        )
        check(started) { "layoutlib did not start: " + problems.joinToString("; ").ifEmpty { "no reason given" } }
        return bridge
    }

    /**
     * attribute name -> (enum or flag name -> value), from the framework's `attrs.xml`. layoutlib reads
     * `match_parent`, `vertical` and every other named value through this table; given an empty one it still
     * starts, and then measures every view at zero.
     */
    private fun enumValues(attrsXml: File): Map<String, Map<String, Int>> {
        val out = HashMap<String, MutableMap<String, Int>>()
        val attrs = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(attrsXml).getElementsByTagName("attr")
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) as org.w3c.dom.Element
            val children = attr.childNodes
            for (k in 0 until children.length) {
                val child = children.item(k) as? org.w3c.dom.Element ?: continue
                if (child.tagName != "enum" && child.tagName != "flag") continue
                out.getOrPut(attr.getAttribute("name")) { HashMap() }[child.getAttribute("name")] = java.lang.Long.decode(child.getAttribute("value")).toInt()
            }
        }
        return out
    }

    // ---- per project ---------------------------------------------------------------------------------------

    private fun projectFor(request: LayoutlibRequest, config: FolderConfiguration, configText: String): Project {
        val built = request.builtResourceIds?.takeIf { it.isFile }
        val key = listOf(request.resourceDirs, request.classpath, request.resourcePackages, configText, stamp(request.resourceDirs), built?.let { it.path + it.lastModified() + it.length() })
        project?.takeIf { it.key == key }?.let { return it }
        val framework = frameworkTables.getOrPut(configText) {
            ResourceTables.load(File(request.cacheDir, "framework-$configText.bin"), ResourceNamespace.ANDROID, listOf(home.frameworkRes()), config, configText)
        }
        val name = "project-" + Integer.toHexString(request.resourceDirs.map { it.path }.hashCode()) + "-$configText.bin"
        val app = ResourceTables.load(File(request.cacheDir, name), ResourceNamespace.RES_AUTO, request.resourceDirs, config, configText)
        val ids = ResourceIds(app, request.resourcePackages, { attr -> Bridge.getResourceId(ResourceType.ATTR, attr) ?: 0 }, built)
        val classes = LibraryClassLoader(request.classpath, LayoutlibRenderer::class.java.classLoader, ids)
        return Project(key, mapOf(ResourceNamespace.ANDROID to framework, ResourceNamespace.RES_AUTO to app), ids, classes).also { project = it }
    }

    /** Changes when a resource file is saved, so an edit to `colors.xml` is seen by the next render. */
    private fun stamp(dirs: List<File>): Long {
        var h = 0L
        // The project's own folder is the one edited between renders; libraries are covered by the cache's print.
        dirs.lastOrNull()?.walkTopDown()?.filter { it.isFile }?.forEach { h = 31 * h + it.lastModified() + it.length() }
        return h
    }

    private inner class Callback(private val project: Project) : LayoutlibCallback() {
        /**
         * A view that cannot be made (its class is not on the classpath yet, its constructor threw) is drawn as
         * layoutlib's own labelled stand-in and reported, and the rest of the layout renders around it: what
         * the device preview does, where failing the whole frame for one view would show nothing at all.
         */
        override fun loadView(name: String, signature: Array<out Class<*>>, args: Array<out Any?>): Any = try {
            project.classes.loadClass(name).getConstructor(*signature).newInstance(*args)
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            if (problems.size < MAX_PROBLEMS) problems += name + "\t" + cause.toString()
            val standIn = com.android.layoutlib.bridge.MockView::class.java.getConstructor(*signature).newInstance(*args)
            standIn.setText(name.substringAfterLast('.'))
            standIn
        }

        override fun findClass(name: String): Class<*> = project.classes.loadClass(name)
        override fun resolveResourceId(id: Int): ResourceReference? = project.ids.referenceOf(id)
        override fun getOrGenerateResourceId(resource: ResourceReference): Int = project.ids.idOf(resource.resourceType, resource.name)
        override fun getParser(layoutResource: ResourceValue): ILayoutPullParser? =
            layoutResource.value?.let { path -> runCatching { FileParser(File(path), layoutResource.namespace) }.getOrNull() }

        override fun getAdapterBinding(viewObject: Any?, attributes: MutableMap<String, String>?): AdapterBinding? = null
        override fun getActionBarCallback(): ActionBarCallback = ActionBarCallback()
        override fun createXmlParserForPsiFile(fileName: String): XmlPullParser? = createXmlParserForFile(fileName)
        override fun createXmlParserForFile(fileName: String): XmlPullParser? =
            runCatching { FileParser(File(fileName), ResourceNamespace.RES_AUTO) }.getOrNull()

        override fun createXmlParser(): XmlPullParser = KXmlParser()
    }

    private class FileParser(file: File, private val namespace: ResourceNamespace) : KXmlParser(), ILayoutPullParser {
        init {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(file.reader())
        }

        override fun getViewCookie(): Any? = null
        override fun getLayoutNamespace(): ResourceNamespace = namespace
    }

    private object Assets : AssetRepository() {
        override fun isSupported(): Boolean = true
        override fun openAsset(path: String, mode: Int): InputStream = FileInputStream(path)
        override fun openNonAsset(cookie: Int, path: String, mode: Int): InputStream = FileInputStream(path)
    }

    private val log = object : ILayoutLog {
        override fun warning(tag: String?, message: String?, viewCookie: Any?, data: Any?) = note(tag, message, null)
        override fun fidelityWarning(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) = note(tag, message, throwable)
        override fun error(tag: String?, message: String?, viewCookie: Any?, data: Any?) = note(tag, message, null)
        override fun error(tag: String?, message: String?, throwable: Throwable?, viewCookie: Any?, data: Any?) = note(tag, message, throwable)
        override fun logAndroidFramework(priority: Int, tag: String?, message: String?) {
            if (priority >= 6) note(tag, message, null)
        }
    }

    private fun note(tag: String?, message: String?, cause: Throwable?) {
        if (problems.size >= MAX_PROBLEMS) return
        var deepest = cause
        while (deepest?.cause != null && deepest.cause !== deepest) deepest = deepest.cause
        problems += (tag ?: "layoutlib") + "\t" + listOfNotNull(message, deepest?.toString()).joinToString(": ")
    }

    // ---- results -------------------------------------------------------------------------------------------

    /**
     * The session's own buffer, read as it is. `RenderSession.getImage()` answers null in this layoutlib, and its
     * replacement copies through Java2D, which a JDK with no AWT native library (the one on iOS) cannot do. The
     * buffer comes from a pool and can be wider than the picture, so rows are copied at the picture's width.
     */
    private fun pixelsOf(session: RenderSession): Triple<IntArray, Int, Int>? {
        val impl = session.javaClass.getDeclaredField("mSession").apply { isAccessible = true }.get(session)
        fun field(name: String) = impl.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val image = field("mImage").get(impl) as? BufferedImage ?: return null
        val width = field("mImageWidth").getInt(impl)
        val height = field("mImageHeight").getInt(impl)
        val source = (image.raster.dataBuffer as DataBufferInt).data
        val stride = image.width
        if (stride == width) return Triple(source.copyOf(width * height), width, height)
        val out = IntArray(width * height)
        for (row in 0 until height) System.arraycopy(source, row * stride, out, row * width, width)
        return Triple(out, width, height)
    }

    /** layoutlib reports each view's bounds inside its parent; the captured tree is in the picture's own pixels. */
    private fun capture(info: ViewInfo, parentLeft: Int, parentTop: Int, project: Project): PreviewViewNode {
        val left = parentLeft + info.left
        val top = parentTop + info.top
        val view = info.viewObject as? android.view.View
        val id = view?.id?.takeIf { it != android.view.View.NO_ID }?.let { raw ->
            (project.ids.referenceOf(raw) ?: Bridge.resolveResourceId(raw))?.name
        }
        val properties = buildList {
            add(PreviewViewProperty("Layout", "x", left.toString()))
            add(PreviewViewProperty("Layout", "y", top.toString()))
            add(PreviewViewProperty("Layout", "width", (info.right - info.left).toString()))
            add(PreviewViewProperty("Layout", "height", (info.bottom - info.top).toString()))
            (view as? android.widget.TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }?.let { add(PreviewViewProperty("Text", "text", it)) }
        }
        return PreviewViewNode(
            className = info.className, id = id, left = left, top = top,
            right = parentLeft + info.right, bottom = parentTop + info.bottom,
            properties = properties, children = info.children.map { capture(it, left, top, project) },
        )
    }

    private companion object {
        const val MAX_PROBLEMS = 50
        /** One frame of a 60 Hz display. */
        const val FRAME_NANOS = 16_666_667L
    }
}
