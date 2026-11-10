package dev.ide.core.backend

import dev.ide.android.layout.AndroidLayoutModel
import dev.ide.android.layout.ElementRef
import dev.ide.android.layout.LayoutAttribute
import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.LayoutDocument
import dev.ide.android.layout.LayoutEdit
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layout.LayoutImage
import dev.ide.android.layout.LayoutProblem
import dev.ide.android.layout.LayoutRenderRequest
import dev.ide.android.layout.LayoutRenderResult
import dev.ide.android.layout.LayoutRenderer
import dev.ide.android.layout.LayoutResourceError
import dev.ide.android.layout.LayoutValueCandidate
import dev.ide.android.layout.LayoutValueCompletion
import dev.ide.android.layout.LayoutViewNode
import dev.ide.android.layout.LayoutViewProperty
import dev.ide.android.support.metadata.AttributeSpec
import dev.ide.core.BackendContext
import dev.ide.core.IdeServices
import dev.ide.lang.dom.XmlNodeKinds
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlTreeParser
import dev.ide.platform.EngineCanceledException
import dev.ide.preview.LayoutPreviewResult
import dev.ide.preview.PreviewRequest
import dev.ide.preview.PreviewViewNode
import dev.ide.preview.RenderNode
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.nio.file.Paths

/**
 * Draws an owned-renderer result into pixels: lays out [LayoutPreviewResult.root] (its nodes' bounds are read
 * afterwards) and returns packed ARGB, or null when it cannot. The pixels need a text and paint backend the
 * engine tier does not have, so the launcher supplies one (the Compose-backed `rasterizeOwnedLayout`).
 */
fun interface OwnedLayoutRasterizer {
    fun rasterize(result: LayoutPreviewResult, widthPx: Int, heightPx: Int): IntArray?
}

/**
 * The host's [LayoutRenderer]: the editor's own layout preview pipeline, answered as an image plus the view
 * tree that produced it.
 *
 * The real-view path (a device) hands back the framework's own bitmap and the hierarchy it captured, already
 * stamped with source offsets. The owned path (desktop, or before the libraries are prepared) is drawn here
 * through [rasterizer], and its tree is built from the laid-out render nodes, whose offsets the inflater
 * stamped. Either way the caller sees one shape.
 *
 * Bound to one project's [services]; registered on that project's workspace container.
 */
internal class HostLayoutRenderer(
    private val ctx: BackendContext,
    private val services: IdeServices,
    private val rasterizer: () -> OwnedLayoutRasterizer?,
) : LayoutRenderer {

    override val progress: StateFlow<String?> = MappedStateFlow(services.realViewProgress) { it?.stage }

    override suspend fun render(request: LayoutRenderRequest): LayoutRenderResult {
        val preview = PreviewRequest(
            widthPx = request.widthPx,
            heightPx = request.heightPx,
            density = request.density,
            showChrome = request.showSystemUi,
            night = request.night,
            realViews = true,
        )
        val r = ctx.preview("layoutRender") { services.layoutPreview(Paths.get(request.path), request.text, preview) }
            ?: return LayoutRenderResult(image = null, root = null, problems = listOf(LayoutProblem(null, "This file is not a layout of an Android module.")))
        val errors = r.resourceErrors.map { LayoutResourceError(it.file, it.line, it.message) }
        val problems = r.problems.map { LayoutProblem(it.tag, it.message) }
        val nativeImage = r.renderedNativeImage
        val png = r.renderedImage
        if (nativeImage != null || png != null) {
            return LayoutRenderResult(
                image = LayoutImage(request.widthPx, request.heightPx, bitmap = nativeImage, png = png),
                root = r.viewTree?.let(::fromCaptured),
                problems = problems,
                resourceErrors = errors,
                librariesNeedPreparing = r.buildRequired,
                unpreparedLibraryCount = r.undexedCount,
            )
        }
        // Owned: draw it here. The rasterizer lays the tree out as it draws, so the bounds are read after.
        val pixels = rasterizer()?.rasterize(r, request.widthPx, request.heightPx)
        if (pixels == null) layoutHeadless(r, request.widthPx, request.heightPx)
        return LayoutRenderResult(
            image = pixels?.let { LayoutImage(request.widthPx, request.heightPx, argb = it) },
            root = fromOwned(r.root),
            problems = problems,
            resourceErrors = errors,
            librariesNeedPreparing = r.buildRequired,
            unpreparedLibraryCount = r.undexedCount,
            approximate = true,
        )
    }

    override fun prepareLibraries(path: String): Boolean {
        val module = services.moduleForEditableFile(Paths.get(path)) ?: return false
        val variant = services.activeVariant(module.name) ?: "debug"
        ctx.buildRunnerFor(services).runTask("prepareDex:${module.name}:$variant")
        return true
    }

    /** No rasterizer (a headless test): measure with fixed-ratio text so the tree still has real bounds. */
    private fun layoutHeadless(r: LayoutPreviewResult, w: Int, h: Int) {
        runCatching {
            dev.ide.preview.PreviewEngine(
                dev.ide.preview.SimpleRenderContext(
                    dev.ide.preview.impl.headless.HeadlessGraphics(), r.resources, r.density, r.density,
                ),
            ).measureAndLayout(r.root, w, h)
        }
    }

    private fun fromCaptured(n: PreviewViewNode): LayoutViewNode = LayoutViewNode(
        className = n.className,
        id = n.id,
        left = n.left, top = n.top, right = n.right, bottom = n.bottom,
        sourceOffset = n.sourceOffset,
        properties = n.properties.map { LayoutViewProperty(it.group, it.name, it.value) },
        children = n.children.map(::fromCaptured),
    )

    private fun fromOwned(n: RenderNode): LayoutViewNode = LayoutViewNode(
        className = n.tag.ifEmpty { "View" },
        id = n.props.id,
        left = n.left, top = n.top, right = n.right, bottom = n.bottom,
        sourceOffset = n.sourceOffset,
        children = n.children.map(::fromOwned),
    )
}

/**
 * The host's [AndroidLayoutModel]: the editor's tolerant XML parser, and the SDK/library/project attribute
 * metadata and value completion the XML editor itself uses, so a design surface and the code editor never
 * disagree about what a layout may say.
 */
internal class HostAndroidLayoutModel(
    private val ctx: BackendContext,
    private val services: IdeServices,
) : AndroidLayoutModel {

    override fun parse(text: String): LayoutDocument = parseLayoutDocument(text)

    override suspend fun attributes(path: String, tag: String, parentTag: String?): List<LayoutAttributeInfo> =
        ctx.background("layoutAttributes") { services.layoutAttributeSpecs(Paths.get(path), tag, parentTag) }
            .orEmpty()
            .map(::toInfo)

    override suspend fun completeValue(
        path: String, text: String, element: ElementRef, attribute: String, fieldText: String, caret: Int,
    ): LayoutValueCompletion = try {
        val r = ctx.interactive("layoutValueCompletion") {
            services.completeLayoutAttributeValue(Paths.get(path), text, element.offset, element.id, attribute, fieldText, caret)
        }
        LayoutValueCompletion(
            candidates = r.items.map {
                LayoutValueCandidate(it.label, it.insertText, it.detail, it.kind.name.lowercase())
            },
            replaceStart = r.replacementRange.start,
            replaceEnd = r.replacementRange.end,
        )
    } catch (_: EngineCanceledException) {
        LayoutValueCompletion(emptyList(), 0, fieldText.length)
    }

    override suspend fun setAttribute(
        path: String, text: String, element: ElementRef, attribute: String, value: String,
    ): List<LayoutEdit> = ctx.background("layoutSetAttribute") {
        services.setLayoutAttributeEdits(Paths.get(path), text, element.offset, element.id, attribute, value)
    }.map { LayoutEdit(it.range.start, it.range.end, it.newText) }

    override suspend fun removeAttribute(
        path: String, text: String, element: ElementRef, attribute: String,
    ): List<LayoutEdit> = ctx.background("layoutRemoveAttribute") {
        services.removeLayoutAttributeEdits(Paths.get(path), text, element.offset, element.id, attribute)
    }.map { LayoutEdit(it.range.start, it.range.end, it.newText) }

    override suspend fun resources(path: String, type: String): List<dev.ide.android.layout.LayoutResource> =
        ctx.background("layoutResources") { services.layoutResources(Paths.get(path), type) }.orEmpty().map {
            dev.ide.android.layout.LayoutResource(type, it.name, "@$type/${it.name}", it.value, it.file, it.argb)
        }

    override suspend fun themeColors(path: String): List<dev.ide.android.layout.LayoutResource> =
        ctx.background("layoutThemeColors") { services.layoutThemeColors(Paths.get(path)) }.orEmpty().map { (name, argb) ->
            dev.ide.android.layout.LayoutResource("attr", name, "?attr/$name", argb = argb)
        }

    private fun toInfo(a: AttributeSpec): LayoutAttributeInfo {
        val types = a.resourceTypes.map { it.rClass }
        val formats = buildSet {
            if (a.boolean) add("boolean")
            if (a.enumValues.isNotEmpty()) add("enum")
            if (a.flags.isNotEmpty()) add("flags")
            for (t in types) add(
                when (t) {
                    "dimen" -> "dimension"
                    "color" -> "color"
                    "string" -> "string"
                    "integer" -> "integer"
                    "bool" -> "boolean"
                    "fraction" -> "fraction"
                    else -> "reference"
                },
            )
            if (isEmpty()) add("string")
        }
        return LayoutAttributeInfo(a.name, formats, a.enumValues, a.flags, types, a.doc)
    }
}

/**
 * Parse a layout buffer with the editor's tolerant XML parser into the plugin-facing element tree. Internal
 * (not private) so it is testable without a project.
 */
internal fun parseLayoutDocument(text: String): LayoutDocument {
    val document = runCatching { XmlTreeParser.parse("layout.xml", text) }.getOrNull()
        ?: return LayoutDocument(text, null)
    // The PSI projection always yields a tree, even for an empty buffer (a nameless placeholder tag): only a
    // named element is one the user wrote.
    val rootTag = document.childTags.firstOrNull { !it.name.isNullOrEmpty() } ?: return LayoutDocument(text, null)
    return LayoutDocument(text, toElement(rootTag, text))
}

private fun toElement(tag: XmlNode, text: String): LayoutElement {
    val attrs = tag.attributes.mapNotNull { a ->
        val name = a.name ?: a.text().toString().substringBefore('=').trim().ifEmpty { return@mapNotNull null }
        val vn = a.valueNode
        LayoutAttribute(
            name = name,
            value = vn?.text()?.toString() ?: "",
            start = a.startOffset,
            end = a.endOffset,
            valueStart = vn?.startOffset ?: a.endOffset,
            valueEnd = vn?.endOffset ?: a.endOffset,
        )
    }
    return LayoutElement(
        tag = tag.name ?: "",
        start = tag.startOffset,
        end = tag.endOffset,
        startTagEnd = startTagEnd(tag, text),
        selfClosing = tag.selfClosed,
        attributes = attrs,
        children = tag.childTags.filter { it.kind == XmlNodeKinds.TAG && !it.name.isNullOrEmpty() }.map { toElement(it, text) },
    )
}

/** Just past the `>` (or `/>`) that ends [tag]'s start tag, scanning from its last attribute; quotes respected. */
private fun startTagEnd(tag: XmlNode, text: String): Int {
    var i = (tag.attributes.maxOfOrNull { it.endOffset } ?: (tag.startOffset + 1 + (tag.name?.length ?: 0)))
        .coerceIn(0, text.length)
    val limit = tag.endOffset.coerceIn(i, text.length)
    var quote: Char? = null
    while (i < limit) {
        val c = text[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i + 1
        }
        i++
    }
    return limit
}

/**
 * A read-only [StateFlow] that is [source] seen through [transform]. The engine publishes its preview stage as
 * a UI DTO; the SPI speaks a plain string, and a mapped view keeps one source of truth instead of a second
 * flow kept in step by a coroutine.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<A, B>(
    private val source: StateFlow<A>,
    private val transform: (A) -> B,
) : StateFlow<B> {
    override val value: B get() = transform(source.value)
    override val replayCache: List<B> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<B>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
