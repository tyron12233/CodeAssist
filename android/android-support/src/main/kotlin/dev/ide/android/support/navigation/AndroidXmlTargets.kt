package dev.ide.android.support.navigation

import dev.ide.android.support.resources.ResourceReferences
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.dom.XmlNodeKinds
import dev.ide.lang.xml.XmlNode
import dev.ide.lang.xml.XmlTreeParser

/**
 * What the caret is on in an Android XML file (or on an `R.type.name` access in code), as far as navigation,
 * Quick Doc and rename care. [range] is the span the target covers in the buffer, end-exclusive: for a
 * [Resource] it is the NAME only (`app_name` in `@string/app_name`), so a rename can replace it as is.
 */
sealed interface XmlNavTarget {
    val range: IntRange

    /**
     * A resource. [name] is as written (a style keeps its dots: `Theme.App`); compare through [rName], the
     * `R` field it becomes. [packageName] is null for a local reference, `"android"` for the framework.
     * [declaration] is set when the caret is on the resource's own declaration (`name="…"` in `values`,
     * `@+id/…` on an `android:id`) rather than on a use of it.
     */
    data class Resource(
        val type: ResourceType,
        val name: String,
        val packageName: String?,
        val declaration: Boolean,
        override val range: IntRange,
    ) : XmlNavTarget {
        val rName: String get() = sanitizeResourceName(name)
        val isFramework: Boolean get() = packageName == "android"
    }

    /** A Java/Kotlin class named in XML: a custom view tag, a manifest component, `tools:context`, ... */
    data class ClassRef(val fqn: String, override val range: IntRange) : XmlNavTarget

    /** An attribute NAME (`android:text`, `app:cardElevation`), for its declaration and Quick Doc. [tag] and
     *  [parentTag] are the element it sits on and that element's parent, for looking its spec up. */
    data class Attribute(
        val prefix: String?,
        val localName: String,
        val tag: String?,
        val parentTag: String?,
        override val range: IntRange,
    ) : XmlNavTarget {
        val isFramework: Boolean get() = prefix == "android"
    }

    /** `android:onClick="handler"`: the [method] and the layout's `tools:context` class, when it names one. */
    data class OnClick(val method: String, val contextClass: String?, override val range: IntRange) : XmlNavTarget
}

/** The `R` field name a resource name becomes (`Theme.App` → `Theme_App`). */
fun sanitizeResourceName(name: String): String = name.replace('.', '_').replace('-', '_').trim()

/**
 * Classifies the caret in an Android XML buffer into an [XmlNavTarget]. Pure: the tolerant XML parse plus a
 * few Android conventions (which attributes name classes, how a manifest's `.Foo` resolves against the
 * module namespace). The engine decides what each target navigates to.
 */
object AndroidXmlTargets {

    /** Attributes (by full name) whose value is a class name, on any element. */
    private val CLASS_ATTRIBUTES = setOf(
        "tools:context", "android:targetActivity", "android:parentActivityName", "android:backupAgent",
        "android:manageSpaceActivity", "app:layoutManager", "app:layout_behavior", "app:actionViewClass",
        "app:actionProviderClass", "android:actionViewClass", "android:actionProviderClass",
    )

    /** Elements whose `android:name` is a class (manifest components, fragments, nav-graph destinations). */
    private val NAME_IS_CLASS_TAGS = setOf(
        "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation",
        "fragment", "dialog", "androidx.fragment.app.FragmentContainerView",
    )

    /** Tags that only look like elements: never a view class. */
    private val NON_VIEW_TAGS = setOf(
        "merge", "include", "fragment", "requestFocus", "tag", "view", "layout", "data", "variable", "import",
        "blink",
    )

    /** Framework views whose package is not `android.widget` (what `LayoutInflater` tries first). */
    private val FRAMEWORK_VIEW_PACKAGES = mapOf(
        "View" to "android.view", "ViewGroup" to "android.view", "ViewStub" to "android.view",
        "SurfaceView" to "android.view", "TextureView" to "android.view", "WebView" to "android.webkit",
    )

    /**
     * The target at [offset] in [text] (the live buffer of [path]). [namespace] is the module's package, used
     * for a manifest component written relative to it (`.MainActivity`). Null when nothing navigable is there.
     */
    fun at(path: String, text: String, offset: Int, namespace: String?): XmlNavTarget? {
        val root = runCatching { XmlTreeParser.parse(path, text) }.getOrNull() ?: return null
        val node = deepestAt(root, offset) ?: return null
        val isLayout = folderOf(path).substringBefore('-') == "layout"
        return when (node.kind) {
            XmlNodeKinds.ATTR_VALUE -> valueTarget(root, node, offset, namespace)
            XmlNodeKinds.ATTRIBUTE -> {
                val name = node.name ?: return null
                val nameEnd = node.startOffset + name.length
                if (offset > nameEnd) return null
                val tag = node.parent as? XmlNode
                XmlNavTarget.Attribute(
                    prefix = name.substringBefore(':', "").ifEmpty { null },
                    localName = name.substringAfter(':'),
                    tag = tag?.name,
                    parentTag = (tag?.parent as? XmlNode)?.takeIf { it.kind == XmlNodeKinds.TAG }?.name,
                    range = node.startOffset until nameEnd,
                )
            }
            XmlNodeKinds.TAG -> tagNameTarget(node, text, offset, isLayout)
            else -> null
        }
    }

    /** The resource a file-based resource ([path] under `res/<type>[-quals]/`) declares, or null for `values`
     *  and anything outside `res/`. */
    fun fileResource(path: String): Pair<ResourceType, String>? {
        val folder = folderOf(path)
        val type = ResourceType.fromFolder(folder.substringBefore('-')) ?: return null
        val norm = path.replace('\\', '/')
        if (!norm.contains("/res/")) return null
        val name = norm.substringAfterLast('/').substringBefore('.')
        return type to name
    }

    /** The class an `android:name`/`tools:context`-style [value] names, resolved against [namespace]:
     *  `.Foo` and a manifest's bare `Foo` are relative to it. Null for a blank or non-class value. */
    fun resolveClassName(value: String, namespace: String?, relativeBare: Boolean = false): String? {
        val v = value.trim()
        if (v.isEmpty() || v.startsWith("@") || v.startsWith("?") || v.startsWith("{")) return null
        if (!v.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' }) return null
        return when {
            v.startsWith(".") -> namespace?.let { it + v }
            '.' !in v && relativeBare -> namespace?.let { "$it.$v" }
            else -> v
        }?.replace('$', '.')
    }

    /** The `tools:context` class of [root]'s first element, resolved against [namespace]. */
    fun toolsContext(path: String, text: String, namespace: String?): String? {
        val root = runCatching { XmlTreeParser.parse(path, text) }.getOrNull() ?: return null
        return toolsContextOf(root, namespace)
    }

    private fun toolsContextOf(root: XmlNode, namespace: String?): String? {
        val first = root.childTags.firstOrNull() ?: return null
        return attrValue(first, "tools:context")?.let { resolveClassName(it, namespace) }
    }

    private fun valueTarget(root: XmlNode, valueNode: XmlNode, offset: Int, namespace: String?): XmlNavTarget? {
        val attr = valueNode.parent as? XmlNode ?: return null
        val attrName = attr.name ?: return null
        val tag = attr.parent as? XmlNode ?: return null
        val tagName = tag.name.orEmpty()
        val value = valueNode.text().toString()
        val start = valueNode.startOffset
        val local = offset - start

        // A resource reference anywhere in the value (`@string/x`, `?attr/x`, `@+id/x`, `@android:color/x`).
        ResourceReferences.scan(value).firstOrNull { local >= it.range.first && local <= it.range.last + 1 }?.let { ref ->
            val type = ref.type ?: return null
            if (ref.name in PSEUDO) return null
            val nameStart = start + ref.range.last + 1 - ref.name.length
            val declaration = ref.create && attrName.substringAfter(':') == "id"
            return XmlNavTarget.Resource(type, ref.name, ref.packageName, declaration, nameStart until nameStart + ref.name.length)
        }
        val whole = start until start + value.length

        when {
            // `<style parent="Theme.Material3">`: a raw style name.
            tagName == "style" && attrName == "parent" && value.isNotBlank() -> {
                val pkg = if (value.startsWith("android:")) "android" else null
                val name = value.removePrefix("android:")
                val nameStart = start + (value.length - name.length)
                return XmlNavTarget.Resource(ResourceType.STYLE, name, pkg, false, nameStart until nameStart + name.length)
            }
            attrName == "name" && isValuesDeclaration(tag) -> {
                val type = valuesTagType(tag) ?: return null
                if (':' in value) return null
                return XmlNavTarget.Resource(type, value, null, true, whole)
            }
            // `<item name="colorPrimary">` in a style names an attribute.
            attrName == "name" && tagName == "item" && (tag.parent as? XmlNode)?.name == "style" -> {
                val pkg = value.substringBefore(':', "").ifEmpty { null }
                val local2 = value.substringAfter(':')
                val nameStart = start + (value.length - local2.length)
                return if (pkg == "android") XmlNavTarget.Attribute("android", local2, null, null, nameStart until nameStart + local2.length)
                else XmlNavTarget.Resource(ResourceType.ATTR, local2, pkg, false, nameStart until nameStart + local2.length)
            }
            attrName == "android:onClick" && value.isNotBlank() ->
                return XmlNavTarget.OnClick(value.trim(), toolsContextOf(root, namespace), whole)
        }

        val classAttr = attrName in CLASS_ATTRIBUTES ||
            ((attrName == "android:name" || attrName == "class") && tagName in NAME_IS_CLASS_TAGS) ||
            (attrName == "class" && tagName == "view") ||
            (attrName == "type" && (tagName == "variable" || tagName == "import"))
        if (classAttr) {
            val manifestComponent = attrName == "android:name" && tagName != "fragment" && tagName != "dialog" &&
                !tagName.contains('.')
            val fqn = when {
                attrName == "app:layoutManager" && '.' !in value -> "androidx.recyclerview.widget.$value"
                else -> resolveClassName(value, namespace, relativeBare = manifestComponent)
            } ?: return null
            return XmlNavTarget.ClassRef(fqn, whole)
        }
        return null
    }

    private fun tagNameTarget(tag: XmlNode, text: String, offset: Int, isLayout: Boolean): XmlNavTarget? {
        val name = tag.name ?: return null
        // A file resource's root element stands for the file itself: its tag name is still a class, but a
        // non-class root (`<menu>`, `<selector>`) falls through to the file resource the caller offers.
        val openStart = tag.startOffset + 1
        val inOpen = offset in openStart..openStart + name.length
        val closeStart = tag.endOffset - name.length - 1
        val inClose = !tag.selfClosed && closeStart > openStart &&
            text.regionMatches(closeStart - 2, "</$name", 0, name.length + 2) &&
            offset in closeStart..closeStart + name.length
        if (!inOpen && !inClose) return null
        val rangeStart = if (inOpen) openStart else closeStart
        val range = rangeStart until rangeStart + name.length
        if ('.' in name) return XmlNavTarget.ClassRef(name, range)
        if (!isLayout || name in NON_VIEW_TAGS || name.firstOrNull()?.isUpperCase() != true) return null
        val pkg = FRAMEWORK_VIEW_PACKAGES[name] ?: "android.widget"
        return XmlNavTarget.ClassRef("$pkg.$name", range)
    }

    /** A direct child of `<resources>` (or an `<attr>` inside a `<declare-styleable>`) that declares by `name`. */
    private fun isValuesDeclaration(tag: XmlNode): Boolean {
        val parent = tag.parent as? XmlNode ?: return false
        return when (parent.name) {
            "resources" -> true
            "declare-styleable" -> tag.name == "attr"
            else -> false
        }
    }

    private fun valuesTagType(tag: XmlNode): ResourceType? = when (tag.name) {
        "item" -> attrValue(tag, "type")?.let { ResourceType.byRClass(it) }
        else -> tag.name?.let { ResourceType.fromValueTag(it) }
    }

    private fun attrValue(tag: XmlNode, name: String): String? =
        tag.attributes.firstOrNull { it.name == name }?.valueNode?.text()?.toString()

    /** The deepest node whose range holds [offset], END-inclusive so a caret just before a closing quote (or
     *  right after a tag name) still counts. Children are preferred over their parent. */
    private fun deepestAt(root: XmlNode, offset: Int): XmlNode? {
        var best: XmlNode? = null
        fun descend(node: XmlNode) {
            for (child in node.children) {
                val c = child as? XmlNode ?: continue
                if (offset >= c.startOffset && offset <= c.endOffset) {
                    best = c
                    descend(c)
                    return
                }
            }
        }
        descend(root)
        return best
    }

    private fun folderOf(path: String): String =
        path.replace('\\', '/').substringBeforeLast('/', "").substringAfterLast('/')

    private val PSEUDO = setOf("null", "empty", "undefined")
}
