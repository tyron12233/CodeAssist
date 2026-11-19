package dev.ide.preview.layoutlib

import dev.ide.preview.PreviewViewNode
import dev.ide.preview.PreviewViewProperty
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The renderer as one static call with flat arguments, for a host that is not on this JVM.
 *
 * The iOS app embeds a JVM and reaches it through JNI, where a call that takes a string and answers a byte
 * array is three lines and a call that takes an object graph is a page. So the request crosses as text and
 * the result as bytes, in the two forms [LayoutlibWire] reads and writes.
 */
object LayoutlibEntry {
    private var renderer: LayoutlibRenderer? = null
    private var rendererHome: String? = null

    /** Render the request [LayoutlibWire.request] wrote. Never throws: a failure is a result with an error. */
    @JvmStatic
    @Synchronized
    fun render(requestText: String): ByteArray {
        val result = try {
            val (home, request) = LayoutlibWire.parseRequest(requestText)
            // layoutlib starts once per process; a second home in the same process cannot be honoured.
            val renderer = renderer ?: LayoutlibRenderer(LayoutlibHome(File(home))).also { renderer = it; rendererHome = home }
            if (rendererHome != home) LayoutlibResult(null, error = "layoutlib is already running from $rendererHome")
            else renderer.render(request)
        } catch (t: Throwable) {
            LayoutlibResult(null, error = t.message ?: t.javaClass.name)
        }
        return LayoutlibWire.encodeResult(result)
    }
}

/**
 * The request as text and the result as bytes.
 *
 * Request: one `key=value` per line; `res`, `cp` and `pkg` repeat, in order.
 *
 * Result, little-endian: the int [MAGIC], width, height, the byte length of the description, the description
 * (UTF-8), then `width * height` ints of premultiplied ARGB. Written little-endian, an ARGB int is the bytes
 * B, G, R, A, which is the BGRA layout Skia and Core Graphics take as it is.
 *
 * The description is lines of tab-separated fields: `error`, `problem`, and the view tree in document order as
 * `node` lines carrying their depth, each followed by its `prop` lines.
 */
object LayoutlibWire {
    const val MAGIC = 0x52564C31 // "RVL1"

    fun request(home: File, request: LayoutlibRequest): String = buildString {
        fun line(key: String, value: Any) = append(key).append('=').append(value).append('\n')
        line("home", home.path)
        line("layout", request.layoutFile.path)
        request.resourceDirs.forEach { line("res", it.path) }
        request.classpath.forEach { line("cp", it.path) }
        request.resourcePackages.forEach { line("pkg", it) }
        line("theme", request.themeName)
        line("width", request.widthPx)
        line("height", request.heightPx)
        line("density", request.density)
        line("night", request.night)
        line("rtl", request.rtl)
        line("minSdk", request.minSdk)
        line("cache", request.cacheDir.path)
    }

    fun parseRequest(text: String): Pair<String, LayoutlibRequest> {
        val single = HashMap<String, String>()
        val lists = HashMap<String, MutableList<String>>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            single[key] = value
            lists.getOrPut(key) { ArrayList() }.add(value)
        }
        fun need(key: String) = single[key] ?: throw IllegalArgumentException("the request has no '$key'")
        val request = LayoutlibRequest(
            layoutFile = File(need("layout")),
            resourceDirs = lists["res"].orEmpty().map(::File),
            classpath = lists["cp"].orEmpty().map(::File),
            resourcePackages = lists["pkg"].orEmpty(),
            themeName = need("theme"),
            widthPx = need("width").toInt(),
            heightPx = need("height").toInt(),
            density = need("density").toFloat(),
            night = single["night"] == "true",
            rtl = single["rtl"] == "true",
            minSdk = single["minSdk"]?.toIntOrNull() ?: 21,
            cacheDir = File(need("cache")),
        )
        return need("home") to request
    }

    fun encodeResult(result: LayoutlibResult): ByteArray {
        val description = buildString {
            result.error?.let { append("error\t").append(flat(it)).append('\n') }
            for (problem in result.problems) append("problem\t").append(problem.replace('\n', ' ')).append('\n')
            result.viewTree?.let { node(it, 0) }
        }.toByteArray(Charsets.UTF_8)
        val pixels = result.pixels
        val width = if (pixels == null) 0 else result.width
        val height = if (pixels == null) 0 else result.height
        val out = ByteBuffer.allocate(16 + description.size + width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(width).putInt(height).putInt(description.size).put(description)
        if (pixels != null) out.asIntBuffer().put(pixels, 0, width * height)
        return out.array()
    }

    private fun StringBuilder.node(node: PreviewViewNode, depth: Int) {
        append("node\t").append(depth).append('\t').append(node.className).append('\t').append(node.id ?: "").append('\t')
            .append(node.left).append('\t').append(node.top).append('\t').append(node.right).append('\t').append(node.bottom).append('\n')
        for (p in node.properties) append("prop\t").append(flat(p.group)).append('\t').append(flat(p.name)).append('\t').append(flat(p.value)).append('\n')
        for (child in node.children) node(child, depth + 1)
    }

    private fun flat(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** The inverse of [encodeResult]. The iOS host has its own copy of this half, in Kotlin/Native. */
    fun decodeResult(bytes: ByteArray): LayoutlibResult {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "not a layoutlib result" }
        val width = buffer.int
        val height = buffer.int
        val description = ByteArray(buffer.int).also { buffer.get(it) }.toString(Charsets.UTF_8)
        val pixels = if (width > 0 && height > 0) IntArray(width * height).also { buffer.asIntBuffer().get(it) } else null

        class Open(val depth: Int, val fields: List<String>) {
            val properties = ArrayList<PreviewViewProperty>()
            val children = ArrayList<PreviewViewNode>()
            fun close() = PreviewViewNode(fields[2], fields[3].ifEmpty { null }, fields[4].toInt(), fields[5].toInt(),
                fields[6].toInt(), fields[7].toInt(), properties, children)
        }
        var error: String? = null
        val problems = ArrayList<String>()
        val stack = ArrayList<Open>()
        var root: PreviewViewNode? = null
        fun closeTo(depth: Int) {
            while (stack.isNotEmpty() && stack.last().depth >= depth) {
                val done = stack.removeAt(stack.size - 1).close()
                if (stack.isEmpty()) root = done else stack.last().children += done
            }
        }
        for (line in description.lineSequence()) {
            val fields = line.split('\t')
            when (fields[0]) {
                "error" -> error = fields.getOrNull(1)
                "problem" -> problems += fields.drop(1).joinToString("\t")
                "node" -> { closeTo(fields[1].toInt()); stack += Open(fields[1].toInt(), fields) }
                "prop" -> stack.lastOrNull()?.properties?.add(PreviewViewProperty(fields[1], fields[2], fields.getOrElse(3) { "" }))
            }
        }
        closeTo(0)
        return LayoutlibResult(pixels, width, height, root, problems, error)
    }
}
