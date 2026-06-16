package dev.ide.preview.impl

import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlTreeParser
import dev.ide.preview.AttrReader
import dev.ide.preview.PlaceholderRenderer
import dev.ide.preview.PreviewProblem
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.RendererRegistry

/**
 * Inflates raw layout XML into the owned [RenderNode] tree using the tolerant `XmlTreeParser` (so a
 * half-typed buffer still previews). A built-in tag resolves through the [registry]; a fully-qualified tag
 * (a `.` in the name) is a user custom view, instantiated through the [customViewFactory]; structural tags
 * are expanded: `<include>`/`<ViewStub>` inflate their referenced layout via [layoutProvider] (name → xml),
 * `<merge>` becomes a transparent container. Anything unknown or un-instantiable becomes a placeholder.
 * Standard `View` attributes are applied to every node so the owned base honours `layout_*`/padding/
 * background/gravity even for custom views (§7).
 */
class LayoutInflater(
    private val registry: RendererRegistry = DefaultRenderers.registry(),
    private val customViewFactory: CustomViewFactory = CustomViewFactory.NONE,
    /** Resolve a layout resource *name* (e.g. `toolbar`) to its raw XML, for `<include>`/`<ViewStub>`. */
    private val layoutProvider: (name: String) -> String? = { null },
) {

    /** Problems collected during the most recent [inflate] (unknown tags, unresolved includes, custom views). */
    val problems: MutableList<PreviewProblem> = ArrayList()

    fun inflate(xml: String, ctx: RenderContext, path: String = "layout.xml"): RenderNode {
        problems.clear()
        val (document, _) = XmlTreeParser(TextDocument(xml, path)).parse()
        val rootEl = document.childTags.firstOrNull() ?: return placeholder("empty")
        return build(rootEl, ctx, depth = 0)
    }

    private fun build(element: XmlNode, ctx: RenderContext, depth: Int): RenderNode {
        val tag = element.name ?: "view"
        val attrs = styled(XmlAttrReader(element), ctx)
        when (tag) {
            "include" -> return buildInclude(attrs, ctx, depth)
            "ViewStub" -> return buildViewStub(attrs, ctx, depth)
            "fragment" -> return placeholder("fragment").also { CommonAttrs.apply(it, attrs, ctx); problem("fragment", "Fragments aren't rendered in preview") }
        }
        val node = nodeFor(tag, attrs, ctx)
        node.tag = tag
        runCatching { node.renderer.applyAttrs(node, attrs, ctx) }
        CommonAttrs.apply(node, attrs, ctx)
        for (childEl in element.childTags) node.children.add(build(childEl, ctx, depth + 1))
        return node
    }

    private fun nodeFor(tag: String, attrs: AttrReader, ctx: RenderContext): RenderNode {
        if (tag == "merge") return RenderNode().apply { renderer = FrameLayoutRenderer }
        // The registry resolves by simple name, so a fully-qualified framework/Material/AppCompat tag (e.g.
        // com.google.android.material.button.MaterialButton) is matched here BEFORE the custom-view path.
        registry.forTag(tag)?.let { r -> return RenderNode().apply { renderer = r } }
        if ('.' in tag) return customNode(tag, attrs, ctx)
        return placeholder(tag).also { problem(tag, "No renderer for <$tag>") }
    }

    /** `<include layout="@layout/x">`: inflate x's root and apply the include tag's `layout_*`/id overrides. */
    private fun buildInclude(attrs: AttrReader, ctx: RenderContext, depth: Int): RenderNode {
        val node = inflateRef(attrs.local("layout"), ctx, depth)
            ?: return placeholder("include").also { problem("include", "Couldn't resolve included layout ${attrs.local("layout")}") }
        CommonAttrs.apply(node, attrs, ctx) // include-tag overrides win over the included root's params
        return node
    }

    /** `<ViewStub android:layout="@layout/x">`: inflate the referenced layout (a stub renders nothing live). */
    private fun buildViewStub(attrs: AttrReader, ctx: RenderContext, depth: Int): RenderNode {
        val node = inflateRef(attrs.android("layout") ?: attrs.local("layout"), ctx, depth)
            ?: return placeholder("ViewStub").also { problem("ViewStub", "Couldn't resolve ViewStub layout") }
        CommonAttrs.apply(node, attrs, ctx)
        return node
    }

    private fun inflateRef(layoutRef: String?, ctx: RenderContext, depth: Int): RenderNode? {
        if (depth > 30) return null // include cycle guard
        val name = layoutRef?.substringAfterLast('/') ?: return null
        val xml = layoutProvider(name) ?: return null
        val (document, _) = XmlTreeParser(TextDocument(xml, "$name.xml")).parse()
        val rootEl = document.childTags.firstOrNull() ?: return null
        return build(rootEl, ctx, depth + 1)
    }

    private fun customNode(fqName: String, attrs: AttrReader, ctx: RenderContext): RenderNode {
        val node = runCatching { customViewFactory.create(fqName, attrs, ctx) }.getOrNull()
            ?: return placeholder(fqName).also { problem(fqName, "Custom view not rendered (no preview runtime)") }
        node.renderer = CustomViewRenderer
        return node
    }

    private fun placeholder(tag: String): RenderNode = RenderNode().apply {
        renderer = PlaceholderRenderer
        this.tag = tag
    }

    private fun problem(tag: String, message: String) { problems.add(PreviewProblem(tag, message)) }

    /** If the element carries `style="@style/…"`, overlay that style's items beneath its explicit attrs. */
    private fun styled(base: XmlAttrReader, ctx: RenderContext): AttrReader {
        val styleRef = base.local("style") ?: return base
        val res = ctx.res as? ProjectPreviewResources ?: return base
        val items = res.styleItems(styleRef.substringAfterLast('/'))
        return if (items.isEmpty()) base else StyleAttrReader(base, items)
    }
}
