package dev.ide.android.layout.remote

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
import dev.ide.android.layout.LayoutResource
import dev.ide.android.layout.LayoutResourceError
import dev.ide.android.layout.LayoutValueCandidate
import dev.ide.android.layout.LayoutValueCompletion
import dev.ide.android.layout.LayoutViewClass
import dev.ide.android.layout.LayoutViewNode
import dev.ide.android.layout.LayoutViewProperty
import dev.ide.android.layout.ViewOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The layout services of a host that lives somewhere else: on the other side of a byte channel.
 *
 * The iOS app has no JVM backend of its own, but it embeds a JVM in which the same backend the Android app
 * uses is running. The layout editor asks for a [LayoutRenderer] and an [AndroidLayoutModel] as it does
 * everywhere; here they are these two, which turn each call into bytes, send it across, and read the answer
 * back. On the far side [LayoutServiceDispatcher] hands each call to the real services, so what answers is the
 * same code that answers on a phone.
 *
 * [channel] is one request in, one response out, and may block; calls leave the caller's thread first.
 */
class RemoteLayoutServices(private val channel: (ByteArray) -> ByteArray) {

    private val stage = MutableStateFlow<String?>(null)

    private fun call(method: Int, write: WireOut.() -> Unit): WireIn {
        val out = WireOut()
        out.int(method)
        out.write()
        val reply = WireIn(channel(out.done()))
        if (!reply.bool()) throw RemoteLayoutException(reply.string())
        return reply
    }

    private suspend fun <T> remote(method: Int, write: WireOut.() -> Unit, read: WireIn.() -> T): T =
        withContext(Dispatchers.Default) { call(method, write).read() }

    val renderer: LayoutRenderer = object : LayoutRenderer {
        override val progress: StateFlow<String?> get() = stage

        override suspend fun render(request: LayoutRenderRequest): LayoutRenderResult {
            stage.value = "Rendering"
            try {
                return remote(LayoutWire.RENDER, { LayoutWire.write(this, request) }) { LayoutWire.readRenderResult(this) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The far side could not be reached or could not answer. A render that throws leaves the editor
                // waiting for a frame that is never coming; one that says why gives it something to show.
                return LayoutRenderResult(
                    image = null, root = null,
                    problems = listOf(LayoutProblem(null, e.message ?: e.toString())),
                    widthPx = request.widthPx, heightPx = request.heightPx,
                )
            } finally {
                stage.value = null
            }
        }

        override fun prepareLibraries(path: String): Boolean =
            runCatching { call(LayoutWire.PREPARE_LIBRARIES) { string(path) }.bool() }.getOrDefault(false)
    }

    val model: AndroidLayoutModel = object : AndroidLayoutModel {
        // Not a suspend function in the interface: the editor parses on the thread it is drawing from, and a
        // layout file parses in well under a frame.
        override fun parse(text: String): LayoutDocument =
            runCatching { LayoutWire.readDocument(call(LayoutWire.PARSE) { string(text) }, text) }.getOrElse { LayoutDocument(text, null) }

        override suspend fun attributes(path: String, tag: String, parentTag: String?): List<LayoutAttributeInfo> =
            remote(LayoutWire.ATTRIBUTES, { string(path); string(tag); string(parentTag) }) { list { LayoutWire.readAttributeInfo(this) } }

        override suspend fun completeValue(
            path: String, text: String, element: ElementRef, attribute: String, fieldText: String, caret: Int,
        ): LayoutValueCompletion = remote(
            LayoutWire.COMPLETE_VALUE,
            { string(path); string(text); LayoutWire.write(this, element); string(attribute); string(fieldText); int(caret) },
        ) {
            val candidates = list { LayoutValueCandidate(string(), string(), stringOrNull(), stringOrNull()) }
            LayoutValueCompletion(candidates, int(), int())
        }

        override suspend fun setAttribute(path: String, text: String, element: ElementRef, attribute: String, value: String): List<LayoutEdit> =
            remote(LayoutWire.SET_ATTRIBUTE, { string(path); string(text); LayoutWire.write(this, element); string(attribute); string(value) }) {
                list { LayoutEdit(int(), int(), string()) }
            }

        override suspend fun removeAttribute(path: String, text: String, element: ElementRef, attribute: String): List<LayoutEdit> =
            remote(LayoutWire.REMOVE_ATTRIBUTE, { string(path); string(text); LayoutWire.write(this, element); string(attribute) }) {
                list { LayoutEdit(int(), int(), string()) }
            }

        override suspend fun resources(path: String, type: String): List<LayoutResource> =
            remote(LayoutWire.RESOURCES, { string(path); string(type) }) { list { LayoutWire.readResource(this) } }

        override suspend fun themeColors(path: String): List<LayoutResource> =
            remote(LayoutWire.THEME_COLORS, { string(path) }) { list { LayoutWire.readResource(this) } }

        override suspend fun viewClasses(path: String): List<LayoutViewClass> =
            remote(LayoutWire.VIEW_CLASSES, { string(path) }) {
                list { LayoutViewClass(string(), bool(), ViewOrigin.entries[int()], stringOrNull()) }
            }
    }
}

/** A call the far side answered with a failure; [message] is its own. */
class RemoteLayoutException(message: String) : RuntimeException(message)

/**
 * The far side of [RemoteLayoutServices]: reads a call, makes it on the real services, writes the answer.
 * A failure of any kind is an answer too, so the channel never has to carry an exception.
 */
class LayoutServiceDispatcher(private val renderer: LayoutRenderer, private val model: AndroidLayoutModel) {

    suspend fun handle(request: ByteArray): ByteArray = try {
        val input = WireIn(request)
        val out = WireOut()
        out.bool(true)
        when (val method = input.int()) {
            LayoutWire.RENDER -> LayoutWire.write(out, renderer.render(LayoutWire.readRenderRequest(input)))
            LayoutWire.PREPARE_LIBRARIES -> out.bool(renderer.prepareLibraries(input.string()))
            LayoutWire.PARSE -> LayoutWire.write(out, model.parse(input.string()))
            LayoutWire.ATTRIBUTES -> out.list(model.attributes(input.string(), input.string(), input.stringOrNull())) { LayoutWire.write(out, it) }
            LayoutWire.COMPLETE_VALUE -> {
                val r = model.completeValue(input.string(), input.string(), LayoutWire.readElementRef(input), input.string(), input.string(), input.int())
                out.list(r.candidates) { out.string(it.label); out.string(it.insertText); out.string(it.detail); out.string(it.kind) }
                out.int(r.replaceStart)
                out.int(r.replaceEnd)
            }
            LayoutWire.SET_ATTRIBUTE ->
                edits(out, model.setAttribute(input.string(), input.string(), LayoutWire.readElementRef(input), input.string(), input.string()))
            LayoutWire.REMOVE_ATTRIBUTE ->
                edits(out, model.removeAttribute(input.string(), input.string(), LayoutWire.readElementRef(input), input.string()))
            LayoutWire.RESOURCES -> out.list(model.resources(input.string(), input.string())) { LayoutWire.write(out, it) }
            LayoutWire.THEME_COLORS -> out.list(model.themeColors(input.string())) { LayoutWire.write(out, it) }
            LayoutWire.VIEW_CLASSES -> out.list(model.viewClasses(input.string())) {
                out.string(it.tag); out.bool(it.isViewGroup); out.int(it.origin.ordinal); out.string(it.doc)
            }
            else -> throw IllegalArgumentException("unknown layout call $method")
        }
        out.done()
    } catch (t: Throwable) {
        t.printStackTrace()
        WireOut().apply { bool(false); string(t.message ?: t.toString()) }.done()
    }

    private fun edits(out: WireOut, edits: List<LayoutEdit>) = out.list(edits) { out.int(it.start); out.int(it.end); out.string(it.newText) }
}

/** How each type of the layout SPI is written and read. Both ends use this one object, so they cannot disagree. */
internal object LayoutWire {
    const val RENDER = 1
    const val PREPARE_LIBRARIES = 2
    const val PARSE = 3
    const val ATTRIBUTES = 4
    const val COMPLETE_VALUE = 5
    const val SET_ATTRIBUTE = 6
    const val REMOVE_ATTRIBUTE = 7
    const val RESOURCES = 8
    const val THEME_COLORS = 9
    const val VIEW_CLASSES = 10

    fun write(out: WireOut, r: LayoutRenderRequest) {
        out.string(r.path); out.string(r.text); out.int(r.widthPx); out.int(r.heightPx); out.float(r.density)
        out.bool(r.night); out.bool(r.showSystemUi); out.float(r.scale)
    }

    fun readRenderRequest(i: WireIn) = LayoutRenderRequest(i.string(), i.string(), i.int(), i.int(), i.float(), i.bool(), i.bool(), i.float())

    fun write(out: WireOut, r: LayoutRenderResult) {
        val image = r.image
        out.bool(image != null)
        if (image != null) {
            out.int(image.width); out.int(image.height)
            // A platform bitmap cannot cross; the far side answers in pixels or an encoded image.
            out.ints(image.argb); out.bytes(image.png)
        }
        out.bool(r.root != null)
        r.root?.let { write(out, it) }
        out.list(r.problems) { out.string(it.tag); out.string(it.message) }
        out.list(r.resourceErrors) { out.string(it.file); out.optionalInt(it.line); out.string(it.message) }
        out.bool(r.librariesNeedPreparing); out.int(r.unpreparedLibraryCount); out.bool(r.approximate)
        out.int(r.widthPx); out.int(r.heightPx)
    }

    fun readRenderResult(i: WireIn): LayoutRenderResult {
        val image = if (i.bool()) LayoutImage(i.int(), i.int(), argb = i.intsOrNull(), png = i.bytesOrNull()) else null
        val root = if (i.bool()) readNode(i) else null
        val problems = i.list { LayoutProblem(i.stringOrNull(), i.string()) }
        val errors = i.list { LayoutResourceError(i.stringOrNull(), i.optionalInt(), i.string()) }
        return LayoutRenderResult(image, root, problems, errors, i.bool(), i.int(), i.bool(), i.int(), i.int())
    }

    private fun write(out: WireOut, n: LayoutViewNode) {
        out.string(n.className); out.string(n.id); out.int(n.left); out.int(n.top); out.int(n.right); out.int(n.bottom)
        out.optionalInt(n.sourceOffset)
        out.list(n.properties) { out.string(it.group); out.string(it.name); out.string(it.value) }
        out.list(n.children) { write(out, it) }
    }

    private fun readNode(i: WireIn): LayoutViewNode = LayoutViewNode(
        i.string(), i.stringOrNull(), i.int(), i.int(), i.int(), i.int(), i.optionalInt(),
        i.list { LayoutViewProperty(i.string(), i.string(), i.string()) },
        i.list { readNode(i) },
    )

    fun write(out: WireOut, e: ElementRef) { out.int(e.offset); out.string(e.id) }
    fun readElementRef(i: WireIn) = ElementRef(i.int(), i.stringOrNull())

    /** The document's text is the caller's own and is not sent back; only the element tree is. */
    fun write(out: WireOut, d: LayoutDocument) {
        out.bool(d.root != null)
        d.root?.let { write(out, it) }
    }

    fun readDocument(i: WireIn, text: String) = LayoutDocument(text, if (i.bool()) readElement(i) else null)

    private fun write(out: WireOut, e: LayoutElement) {
        out.string(e.tag); out.int(e.start); out.int(e.end); out.int(e.startTagEnd); out.bool(e.selfClosing)
        out.list(e.attributes) { a ->
            out.string(a.name); out.string(a.value); out.int(a.start); out.int(a.end); out.int(a.valueStart); out.int(a.valueEnd)
        }
        out.list(e.children) { write(out, it) }
    }

    private fun readElement(i: WireIn): LayoutElement = LayoutElement(
        i.string(), i.int(), i.int(), i.int(), i.bool(),
        i.list { LayoutAttribute(i.string(), i.string(), i.int(), i.int(), i.int(), i.int()) },
        i.list { readElement(i) },
    )

    fun write(out: WireOut, a: LayoutAttributeInfo) {
        out.string(a.name); out.list(a.formats) { out.string(it) }; out.list(a.enumValues) { out.string(it) }
        out.list(a.flagValues) { out.string(it) }; out.list(a.resourceTypes) { out.string(it) }; out.string(a.doc)
    }

    fun readAttributeInfo(i: WireIn) = LayoutAttributeInfo(
        i.string(), i.list { i.string() }.toSet(), i.list { i.string() }, i.list { i.string() }, i.list { i.string() }, i.stringOrNull(),
    )

    fun write(out: WireOut, r: LayoutResource) {
        out.string(r.type); out.string(r.name); out.string(r.reference); out.string(r.value); out.string(r.file)
        out.optionalInt(r.argb); out.bool(r.library)
    }

    fun readResource(i: WireIn) = LayoutResource(i.string(), i.string(), i.string(), i.stringOrNull(), i.stringOrNull(), i.optionalInt(), i.bool())
}
