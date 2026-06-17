package dev.ide.preview

/**
 * The ambient services a renderer pass needs: the graphics factory, the resource resolver, and the previewed
 * device density (px-per-dp / px-per-sp). Threaded through every [Renderer] call so renderers stay stateless.
 */
interface RenderContext {
    val gfx: RGraphics
    val res: PreviewResources
    /** Pixels per dp for the previewed configuration. */
    val density: Float
    /** Pixels per sp (scaled density). */
    val scaledDensity: Float
}

/**
 * A neutral, namespace-aware view of one element's attributes — what the inflater hands a built-in renderer
 * instead of an `android.util.AttributeSet`. The custom-view path uses a real `AttributeSet` (the bridge);
 * this keeps the built-in path android-free.
 */
interface AttrReader {
    val count: Int
    fun name(i: Int): String
    fun namespace(i: Int): String?
    fun value(i: Int): String

    /** The `android:`-namespaced attribute [localName], or null. */
    fun android(localName: String): String?
    /** The `app:` (res-auto) attribute [localName], or null. */
    fun app(localName: String): String?
    /** Any-namespace lookup by local name (android: preferred). */
    fun local(localName: String): String?
}

/**
 * Owned measure/layout/draw for one widget type. Built-ins map XML attributes into [RenderNode.props] in
 * [applyAttrs] and do their own layout; [CustomViewRenderer] delegates the three passes to the node's
 * [ViewHost]. A renderer must never touch a framework view tree — the engine drives traversal (§8).
 */
interface Renderer {
    /** Map neutral attributes into `node.props`. Built-ins override; the custom-view renderer leaves it empty. */
    fun applyAttrs(node: RenderNode, attrs: AttrReader, ctx: RenderContext) {}

    fun measure(node: RenderNode, widthSpec: Int, heightSpec: Int, ctx: RenderContext): Size
    fun layout(node: RenderNode, l: Int, t: Int, r: Int, b: Int, ctx: RenderContext)
    fun draw(node: RenderNode, canvas: RCanvas, ctx: RenderContext)

    companion object {
        /** The stable fallback for unknown widgets / failed renderers — a labelled placeholder box (§12). */
        val FALLBACK: Renderer get() = PlaceholderRenderer
    }
}

/** Registry of built-in renderers keyed by simple tag name (e.g. `TextView`, `LinearLayout`). */
class RendererRegistry {
    private val byTag = HashMap<String, Renderer>()

    fun register(tag: String, renderer: Renderer): RendererRegistry {
        byTag[tag] = renderer
        return this
    }

    fun register(tags: List<String>, renderer: Renderer): RendererRegistry {
        for (t in tags) byTag[t] = renderer
        return this
    }

    /** A built-in renderer for an XML tag (the simple name of a fully-qualified tag), or null if none. */
    fun forTag(tag: String): Renderer? = byTag[tag.substringAfterLast('.')]

    fun has(tag: String): Boolean = forTag(tag) != null
}
