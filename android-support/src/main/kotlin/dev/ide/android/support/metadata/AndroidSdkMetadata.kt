package dev.ide.android.support.metadata

import dev.ide.android.support.resources.ResourceType

/**
 * SDK-derived layout metadata: the single source of layout completion data. Built (offline) from a platform's
 * `attrs.xml` (attribute formats/enums/flags + `<declare-styleable>` groups) and the `android.jar` View class
 * hierarchy, so attribute completion is context-aware via the real hierarchy — `<Button>` offers
 * TextView's and View's attributes, and `layout_*` come from the parent ViewGroup's `*_Layout` styleables
 * (so a `RelativeLayout` child gets `layout_below`/`layout_toEndOf`/…), exactly as Android Studio does.
 *
 * Also reused for custom views: pass [attrPrefix] = `"app:"` and the project/AAR `attrs.xml` styleables,
 * with no hierarchy — completion then offers a custom widget's own attributes (framework View attributes are
 * contributed separately by the framework metadata).
 */
class AndroidSdkMetadata(
    val apiLevel: Int,
    private val attrs: Map<String, AttrEntry>,
    private val styleables: Map<String, StyleableEntry>,
    /** Simple class name → its super's simple name (e.g. `Button` → `TextView`). Empty for custom metadata. */
    private val superclass: Map<String, String>,
    private val widgetList: List<WidgetInfo>,
    private val attrPrefix: String = "android:",
    /**
     * Framework simple name → an equivalent class whose styleable also applies (AndroidX AppCompat's
     * automatic view substitution: a plain `<ImageView>` is inflated as `AppCompatImageView`, gaining
     * `app:srcCompat`/`app:tint`/…). Empty for the framework metadata; the custom (`app:`) metadata passes
     * [APPCOMPAT_SUBSTITUTIONS]. A substitution is a no-op unless the target's styleable is actually present
     * (i.e. AppCompat is on the classpath), so it never invents attributes.
     */
    private val viewSubstitutions: Map<String, String> = emptyMap(),
) : LayoutMetadata {

    data class WidgetInfo(val simpleName: String, val isViewGroup: Boolean)

    private val widgetTags: Set<String> = widgetList.mapTo(HashSet()) { it.simpleName }

    /** Whether [tag] is a known widget (a View subclass from the SDK) — used to decide layout-param inspections. */
    fun isWidgetTag(tag: String): Boolean = widgetTags.contains(tag.substringAfterLast('.'))

    override fun childTagsFor(parentTag: String?): List<Widget> =
        widgetList.map { Widget(it.simpleName, it.isViewGroup) }

    override fun attributesFor(tag: String?, parentTag: String?): List<AttributeSpec> {
        val names = LinkedHashSet<String>()
        names += ownAttrNames(tag)
        names += layoutAttrNames(parentTag)
        // Skip framework internal placeholders (`__removed*`) — defensive, in case an older asset still has them.
        return names.filterNot { it.startsWith("__") }.map(::specFor)
    }

    override fun attribute(tag: String?, parentTag: String?, attributeName: String?): AttributeSpec? {
        if (attributeName == null) return null
        return attributesFor(tag, parentTag).firstOrNull { it.name == attributeName }
    }

    /** Whether this metadata describes any widget tag (false for the custom-view side, which only adds attrs). */
    fun hasWidgets(): Boolean = widgetList.isNotEmpty()

    // ---- internals ----

    private fun superChain(simpleName: String?): List<String> {
        if (simpleName == null) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        var cur: String? = simpleName
        while (cur != null && seen.add(cur)) { out.add(cur); cur = superclass[cur] }
        return out
    }

    private fun ownAttrNames(tag: String?): List<String> {
        val simple = tag?.substringAfterLast('.') ?: return emptyList()
        // The view itself plus any AppCompat/Material substitution (e.g. ImageView → AppCompatImageView), each
        // walked up its ancestry so a view inherits the `app:` attrs declared on its base classes' styleables.
        val roots = LinkedHashSet<String>().apply {
            add(simple)
            viewSubstitutions[simple]?.let(::add)
        }
        // For an unknown class (e.g. a custom view in framework metadata) assume it extends View, so it still
        // gets the universal View attributes.
        val chain = roots.flatMap { superChain(it) }.distinct().ifEmpty { listOf(simple, "View") }
        return chain.flatMap { styleables[it]?.attrs ?: emptyList() }
    }

    private fun layoutAttrNames(parentTag: String?): List<String> {
        val simple = parentTag?.substringAfterLast('.')
        val parentChain = superChain(simple).map { "${it}_Layout" }
        // Margin/layout params are available under any ViewGroup.
        val base = listOf("ViewGroup_MarginLayout_Layout", "ViewGroup_MarginLayout", "ViewGroup_Layout")
        return (parentChain + base).flatMap { styleables[it]?.attrs ?: emptyList() }
    }

    private fun specFor(attrName: String): AttributeSpec {
        val e = attrs[attrName]
        val full = attrPrefix + attrName
        return AttributeSpec(
            name = full,
            enumValues = e?.enumValues ?: emptyList(),
            flags = e?.flagValues ?: emptyList(),
            boolean = e?.formats?.contains(AttrFormat.BOOLEAN) == true,
            resourceTypes = resourceTypesFor(full, e?.formats ?: emptySet()),
        )
    }

    private fun resourceTypesFor(fullName: String, formats: Set<AttrFormat>): List<ResourceType> {
        val out = LinkedHashSet<ResourceType>()
        for (f in formats) when (f) {
            AttrFormat.COLOR -> out += ResourceType.COLOR
            AttrFormat.DIMENSION -> out += ResourceType.DIMEN
            AttrFormat.STRING -> out += ResourceType.STRING
            AttrFormat.INTEGER -> out += ResourceType.INTEGER
            AttrFormat.FRACTION -> out += ResourceType.FRACTION
            AttrFormat.REFERENCE -> referenceHints(fullName).forEach { out += it } // `reference` can't be pinned by format
            else -> {}
        }
        return out.toList()
    }

    /** Best-effort `@type/…` targets for a generic `reference` attribute, inferred from its name. */
    private fun referenceHints(fullName: String): List<ResourceType> {
        val n = fullName.substringAfter(':').lowercase()
        return when {
            // Attributes that reference a view *id*: the `id` declaration, relative/constraint positioning
            // (layout_below/above/to*Of/align*), focus order, label-for, etc. (`alignParent*`/`center*` are
            // boolean in attrs.xml, so they never reach here.)
            isIdReference(n) -> listOf(ResourceType.ID)
            n.contains("drawable") || n == "src" || n.contains("icon") || n.contains("background") ->
                listOf(ResourceType.DRAWABLE, ResourceType.MIPMAP, ResourceType.COLOR)
            n.contains("color") || n.contains("tint") -> listOf(ResourceType.COLOR)
            n.contains("text") || n.contains("title") || n.contains("label") || n.contains("hint") || n.contains("description") ->
                listOf(ResourceType.STRING)
            n.contains("style") || n.contains("theme") -> listOf(ResourceType.STYLE)
            n.endsWith("animation") || n.contains("anim") -> listOf(ResourceType.ANIM, ResourceType.ANIMATOR)
            else -> emptyList()
        }
    }

    private fun isIdReference(n: String): Boolean =
        n == "id" || n == "labelfor" || n == "checkedbutton" || n == "toid" || n == "fromid" ||
            n.startsWith("nextfocus") || n.startsWith("accessibilitytraversal") ||
            (n.startsWith("layout_") && (
                n == "layout_below" || n == "layout_above" ||
                n.startsWith("layout_to") || n.startsWith("layout_align") || n.contains("constraint")
            ))

    companion object {
        /**
         * AndroidX AppCompat's automatic view substitutions (its `AppCompatViewInflater`): a plain framework
         * widget in a layout is inflated as its AppCompat subclass, which carries extra `app:` attributes
         * (`app:srcCompat`, `app:tint`, `app:backgroundTint`, `app:autoSizeTextType`, …). Mapping the framework
         * simple name → the AppCompat simple name lets attribute completion offer those `app:` attrs on the
         * tags people actually type (`<ImageView>`, `<Button>`, …) instead of only on the verbose subclass tag.
         */
        val APPCOMPAT_SUBSTITUTIONS: Map<String, String> = mapOf(
            "TextView" to "AppCompatTextView",
            "Button" to "AppCompatButton",
            "ImageView" to "AppCompatImageView",
            "ImageButton" to "AppCompatImageButton",
            "EditText" to "AppCompatEditText",
            "Spinner" to "AppCompatSpinner",
            "CheckBox" to "AppCompatCheckBox",
            "RadioButton" to "AppCompatRadioButton",
            "CheckedTextView" to "AppCompatCheckedTextView",
            "AutoCompleteTextView" to "AppCompatAutoCompleteTextView",
            "MultiAutoCompleteTextView" to "AppCompatMultiAutoCompleteTextView",
            "RatingBar" to "AppCompatRatingBar",
            "SeekBar" to "AppCompatSeekBar",
            "ToggleButton" to "AppCompatToggleButton",
        )

        @Volatile private var bundledCache: AndroidSdkMetadata? = null

        /** The SDK metadata bundled as a classpath asset (generated by `:android-sdk-metadata`). Cached. */
        fun bundled(): AndroidSdkMetadata {
            bundledCache?.let { return it }
            val md = runCatching {
                AndroidSdkMetadata::class.java.getResourceAsStream("/android-sdk-metadata.txt")
                    ?.bufferedReader()?.use { SdkMetadataCodec.read(it.readText()) }
            }.getOrNull() ?: AndroidSdkMetadata(0, emptyMap(), emptyMap(), emptyMap(), emptyList())
            return md.also { bundledCache = it }
        }
    }
}
