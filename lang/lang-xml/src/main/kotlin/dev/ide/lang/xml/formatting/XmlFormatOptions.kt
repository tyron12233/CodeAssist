package dev.ide.lang.xml.formatting

import dev.ide.lang.formatting.WrapPolicy

/**
 * The XML-only formatting knobs. The shared [dev.ide.lang.formatting.FormatStyle] still supplies the
 * indentation, tab, line-width and blank-line settings; these cover what only markup has. The host resolves
 * them from the XML Code Style profile on every reformat, alongside the style.
 */
data class XmlFormatOptions(
    /**
     * Lay out Android resource and manifest files the way Android Studio does, by file type (see
     * [XmlLayoutRules]). Off, every file follows [wrapAttributes].
     */
    val androidConventions: Boolean = true,
    /** How a start tag's attributes wrap in XML the Android conventions don't cover. */
    val wrapAttributes: WrapPolicy = WrapPolicy.IF_LONG,
    /** A space before the `/>` of an empty tag: `<View />` vs `<View/>`. */
    val spaceInEmptyTag: Boolean = true,
    /** Sort attributes into Android Studio's order (namespaces, id, name, style, layout_width/height, …).
     *  Whole-file reformats only; a selection reformat never moves attributes. */
    val rearrangeAttributes: Boolean = false,
)

/** Which Android file a path is, as far as its layout rules go. */
internal enum class XmlFileKind {
    LAYOUT, MANIFEST, VALUES, RESOURCE, GENERIC;

    companion object {
        /** Resource folders laid out like layouts, minus the blank line before each tag. */
        private val RESOURCE_DIRS = setOf(
            "anim", "animator", "color", "drawable", "font", "interpolator", "menu", "mipmap", "navigation",
            "raw", "transition", "xml",
        )

        fun of(path: String): XmlFileKind {
            val parts = path.replace('\\', '/').split('/')
            if (parts.last() == "AndroidManifest.xml") return MANIFEST
            // Qualifiers don't change the type: layout-land, values-night-v31, drawable-xxhdpi.
            return when (parts.getOrNull(parts.size - 2)?.substringBefore('-')) {
                "layout" -> LAYOUT
                "values" -> VALUES
                in RESOURCE_DIRS -> RESOURCE
                else -> GENERIC
            }
        }
    }
}

/**
 * How one file lays out its tags. With the Android conventions on, these are Android Studio's per-file-type
 * defaults:
 *  - layouts: one attribute per line (the first moves off the tag line too, unless it is the only one or a
 *    namespace declaration), and a blank line before every tag;
 *  - the manifest: the same attribute layout, plus a blank line between runs of differently named tags
 *    (`<uses-permission>`s, then `<application>`; `<action>`, then `<category>`);
 *  - values: attributes stay on the tag line and wrap only when long;
 *  - other resources (drawables, menus, navigation, …): the layout attribute rule without the blank lines.
 */
internal class XmlLayoutRules(
    val wrap: WrapPolicy,
    val breakBeforeFirstAttribute: Boolean,
    val blankLineBeforeTag: Boolean,
    val groupTagBlocks: Boolean,
) {
    companion object {
        fun of(kind: XmlFileKind, options: XmlFormatOptions): XmlLayoutRules {
            val custom = XmlLayoutRules(options.wrapAttributes, false, false, false)
            if (!options.androidConventions) return custom
            return when (kind) {
                XmlFileKind.LAYOUT -> XmlLayoutRules(WrapPolicy.ONE_PER_LINE, true, true, false)
                XmlFileKind.MANIFEST -> XmlLayoutRules(WrapPolicy.ONE_PER_LINE, true, false, true)
                XmlFileKind.RESOURCE -> XmlLayoutRules(WrapPolicy.ONE_PER_LINE, true, false, false)
                XmlFileKind.VALUES -> XmlLayoutRules(WrapPolicy.IF_LONG, false, false, false)
                XmlFileKind.GENERIC -> custom
            }
        }
    }
}
