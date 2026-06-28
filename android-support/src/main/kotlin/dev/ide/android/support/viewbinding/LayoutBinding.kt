package dev.ide.android.support.viewbinding

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The structured shape of one ViewBinding class, derived from a layout XML. This is the single model both
 * the editor (the synthetic [dev.ide.lang.synthetic.SyntheticClass] for completion) and the build (the
 * generated `.java`) render from, so they never drift: same class name, same package, same fields/types.
 *
 * AGP generates a binding per layout from each module's *own* layouts (into `<namespace>.databinding`), with
 * a field per `android:id`, plus `inflate`/`bind` factories and `getRoot()`.
 */
data class BindingClass(
    /** The layout resource name, e.g. `activity_main`. */
    val layoutName: String,
    /** The generated simple class name, e.g. `ActivityMainBinding`. */
    val simpleName: String,
    /** The package, `<namespace>.databinding`. */
    val packageName: String,
    /** Fully-qualified view type of the root element (what `getRoot()` returns). */
    val rootViewType: String,
    /** A field per `android:id` in the layout (union across config variants), in declaration order. */
    val fields: List<BindingField>,
) {
    val fqName: String get() = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
}

/**
 * One generated binding field. [name] is the camelCase Java identifier; [resId] is the original resource id
 * (`foo_bar`, for the `R.id.foo_bar` lookup); [viewType] is the fully-qualified type the field exposes.
 * [kind] tells the build's code generator whether to bind it as a plain `findViewById` view or as a nested
 * `<Other>Binding.bind(...)` (an `<include>` of another in-module layout).
 */
data class BindingField(
    val name: String,
    val resId: String,
    val viewType: String,
    val kind: BindingFieldKind = BindingFieldKind.VIEW,
)

enum class BindingFieldKind { VIEW, BINDING }

/**
 * Parses Android layout XML into [BindingClass]es. Pure (no SDK, no aapt2): it reads the `android:id`s and
 * tag names straight from the layout files, mirroring AGP's `ViewBinding` generation closely enough for
 * type-safe completion and a compilable generated class.
 */
object LayoutBindingModel {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val TOOLS_NS = "http://schemas.android.com/tools"
    const val PACKAGE_SUFFIX = "databinding"

    /** The package generated bindings live in: `<namespace>.databinding` (or just `databinding` if blank). */
    fun packageName(namespace: String): String =
        if (namespace.isBlank()) PACKAGE_SUFFIX else "$namespace.$PACKAGE_SUFFIX"

    /** `activity_main` → `ActivityMainBinding` (segments split on `_`, each capitalized, `Binding` appended). */
    fun bindingClassName(layoutName: String): String =
        layoutName.split('_').filter { it.isNotEmpty() }.joinToString("") { seg ->
            seg.replaceFirstChar { it.uppercaseChar() }
        } + "Binding"

    /** `foo_bar` → `fooBar` (first segment as-is, the rest capitalized) — AGP's id → field-name rule. */
    fun fieldName(id: String): String {
        val segs = id.split('_').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return id
        return segs.first() + segs.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /**
     * All bindings for a module: groups every layout file under [resDirs] by resource name (so config
     * variants merge) and produces one [BindingClass] each, sorted by name for stable, de-duplicated output.
     * Include fields resolve to a nested binding only when the included layout is one of this module's own
     * layouts (so the generated reference always compiles); otherwise they degrade to `android.view.View`.
     */
    fun bindingsFor(resDirs: List<Path>, namespace: String): List<BindingClass> {
        val byName = LinkedHashMap<String, MutableList<Path>>()
        for (res in resDirs) {
            if (!Files.isDirectory(res)) continue
            Files.list(res).use { dirs ->
                dirs.filter { Files.isDirectory(it) && it.fileName.toString().substringBefore('-') == "layout" }
                    .forEach { layoutDir ->
                        Files.list(layoutDir).use { entries ->
                            entries.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                                .forEach { f ->
                                    val name = f.fileName.toString().removeSuffix(".xml")
                                    byName.getOrPut(name) { ArrayList() }.add(f)
                                }
                        }
                    }
            }
        }
        val knownLayouts = byName.keys.toSet()
        return byName.entries
            .sortedBy { it.key }
            .mapNotNull { (name, files) -> fromLayouts(name, files, namespace, knownLayouts) }
    }

    /**
     * Build the binding for one layout from all of its config variants (`layout/`, `layout-land/`, …): the
     * field set is the union of ids across the files (a view present in any config gets a field). [knownLayouts]
     * is the set of in-module layout names used to decide whether an `<include>` is a nested binding. Returns
     * null when the layout opts out via `tools:viewBindingIgnore="true"` on the root, or none of the files parse.
     */
    fun fromLayouts(
        layoutName: String,
        files: List<Path>,
        namespace: String,
        knownLayouts: Set<String> = emptySet(),
    ): BindingClass? {
        val parsed = files.mapNotNull { runCatching { parse(it) }.getOrNull() }
        if (parsed.isEmpty()) return null
        if (parsed.any { it.ignored }) return null

        val fields = LinkedHashMap<String, BindingField>()
        for (p in parsed) for (raw in p.fields) {
            val name = fieldName(raw.id)
            if (fields.containsKey(name)) continue
            fields[name] = resolveField(name, raw, namespace, knownLayouts)
        }
        // Prefer a non-merge root for the getRoot() type; merge layouts root on the attach parent (View).
        val root = parsed.firstOrNull { !it.rootIsMerge }?.let { rootViewType(it.rootTag, it.rootClass) }
            ?: "android.view.View"
        return BindingClass(
            layoutName = layoutName,
            simpleName = bindingClassName(layoutName),
            packageName = packageName(namespace),
            rootViewType = root,
            fields = fields.values.toList(),
        )
    }

    // ---- parsing ----

    private class RawField(val id: String, val tag: String, val classAttr: String, val includeLayout: String?)

    private class ParsedLayout(
        val ignored: Boolean,
        val rootIsMerge: Boolean,
        val rootTag: String,
        val rootClass: String,
        val fields: List<RawField>,
    )

    private fun parse(file: Path): ParsedLayout {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Layouts never carry a DTD/external entities; keep the parser hermetic and fast.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val doc = Files.newInputStream(file).use { factory.newDocumentBuilder().parse(it) }
        val root = doc.documentElement ?: return ParsedLayout(false, false, "View", "", emptyList())

        val rootTag = root.tagName
        val rootIsMerge = rootTag == "merge"
        if (root.getAttributeNS(TOOLS_NS, "viewBindingIgnore") == "true") {
            return ParsedLayout(ignored = true, rootIsMerge, rootTag, root.getAttribute("class"), emptyList())
        }

        val fields = ArrayList<RawField>()
        forEachElement(root) { el ->
            if (el.getAttributeNS(TOOLS_NS, "viewBindingIgnore") == "true") return@forEachElement
            val id = idOf(el) ?: return@forEachElement
            val includeLayout = if (el.tagName == "include") {
                el.getAttribute("layout").takeIf { it.startsWith("@layout/") }?.substringAfter("@layout/")
            } else null
            fields += RawField(id, el.tagName, el.getAttribute("class"), includeLayout)
        }
        return ParsedLayout(false, rootIsMerge, rootTag, root.getAttribute("class"), fields)
    }

    private fun resolveField(name: String, raw: RawField, namespace: String, knownLayouts: Set<String>): BindingField {
        // An <include> of an in-module layout exposes that layout's binding (FooBinding); otherwise a view.
        if (raw.includeLayout != null && raw.includeLayout in knownLayouts) {
            return BindingField(
                name, raw.id,
                "${packageName(namespace)}.${bindingClassName(raw.includeLayout)}",
                BindingFieldKind.BINDING,
            )
        }
        return BindingField(name, raw.id, rootViewType(raw.tag, raw.classAttr))
    }

    /** The fully-qualified view type for a tag (+ its `view class="…"`), with the special tags handled. */
    private fun rootViewType(tag: String, classAttr: String): String = when (tag) {
        "merge" -> "android.view.View"
        "include", "fragment" -> "android.view.View" // no view type of their own
        "view" -> classAttr.ifEmpty { "android.view.View" }
        else -> LayoutTagResolver.resolve(tag)
    }

    /** The local id name of an element's `android:id` (`@+id/foo`/`@id/foo` → `foo`), or null. Skips `@android:id`. */
    private fun idOf(el: Element): String? {
        val raw = el.getAttributeNS(ANDROID_NS, "id").ifEmpty { return null }
        // `@+id/foo`, `@id/foo`, `@+android:id/foo` → take the package + name; framework ids get no field.
        val body = raw.removePrefix("@").removePrefix("+")
        if (body.startsWith("android:")) return null
        val name = body.substringAfterLast('/')
        return name.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    private inline fun forEachElement(root: Element, action: (Element) -> Unit) {
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            action(el)
            val children = el.childNodes
            for (i in 0 until children.length) {
                val n = children.item(i)
                if (n.nodeType == Node.ELEMENT_NODE) stack.addLast(n as Element)
            }
        }
    }
}

/**
 * Resolves a layout tag to the fully-qualified view class, exactly as Android's `LayoutInflater` does: a
 * dotted tag is already qualified (a custom or AndroidX view); a bare name is a framework view, defaulting
 * to `android.widget.*` with the handful of `android.view.*` and `android.webkit.WebView` exceptions.
 */
object LayoutTagResolver {

    /** Framework views that live in `android.view` rather than `android.widget`. */
    private val VIEW_PACKAGE = setOf("View", "ViewGroup", "ViewStub", "SurfaceView", "TextureView")

    fun resolve(tag: String): String {
        if (tag.contains('.')) return tag
        return when {
            tag in VIEW_PACKAGE -> "android.view.$tag"
            tag == "WebView" -> "android.webkit.WebView"
            // Any other bare tag in a real layout is an `android.widget` view (TextView, LinearLayout, …).
            else -> "android.widget.$tag"
        }
    }
}
