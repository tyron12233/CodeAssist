package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttributeSpec
import dev.ide.android.support.resources.AndroidManifestCatalog
import dev.ide.android.support.resources.DrawableXmlCatalog
import dev.ide.lang.xml.lint.AttrInfo
import dev.ide.lang.xml.lint.XmlAttributeChecker

/**
 * The Android half of XML attribute analysis: answers [XmlAttributeChecker]'s schema questions from the same
 * metadata XML completion uses - the SDK-derived [AndroidSdkMetadata] (layouts) and the curated manifest /
 * drawable catalogs. `lang-xml` decides *which* attribute occurrences to check; this decides what Android
 * *allows* there, so the wrong-attribute / wrong-value diagnostics never disagree with completion.
 *
 * Deliberately conservative (it returns [AttrInfo.Indeterminate] unless it is sure):
 *  - **Layouts**: only a known framework widget tag is judged for an unknown `android:` attribute (the SDK
 *    metadata is exhaustive for the framework namespace, so a missing one is a real typo). Non-`android:`
 *    (`app:`/custom) attributes and custom/unknown tags are left alone - the framework metadata can't speak
 *    to library `attrs.xml`. A `layout_*` attribute is only judged when the *parent* is a known widget too
 *    (its layout params come from the parent's styleable).
 *  - **Manifest / drawable**: the curated catalogs are not exhaustive, so an *unknown* attribute is never
 *    flagged there - only a *value* outside a recognized attribute's closed enum/flag/boolean set.
 *
 * A value set is treated as closed (so an out-of-range value is flagged) only when the attribute accepts
 * nothing but enum/flag/boolean values; an attribute that also accepts a dimension/color/string/`@reference`
 * (e.g. `layout_width`, which takes `match_parent` OR `16dp`) is free-form and never value-checked.
 */
class AndroidXmlChecker(
    private val layout: () -> AndroidSdkMetadata = { AndroidSdkMetadata.bundled() },
) : XmlAttributeChecker {

    override fun describe(filePath: String, tag: String, parentTag: String?, attribute: String): AttrInfo {
        val path = filePath.replace('\\', '/')
        return when {
            path.endsWith("AndroidManifest.xml") -> catalogInfo(AndroidManifestCatalog.attribute(tag, attribute))
            DrawableXmlCatalog.appliesTo(path) -> catalogInfo(DrawableXmlCatalog.attribute(tag, attribute))
            else -> layoutInfo(tag, parentTag, attribute)
        }
    }

    /** Layout flavor: judged against the SDK metadata's class hierarchy. */
    private fun layoutInfo(tag: String, parentTag: String?, attribute: String): AttrInfo {
        // The framework metadata only knows the `android:` namespace; app:/custom attrs and unknown tags
        // (custom views, <merge>/<include>, value/menu elements) are left to other sources.
        if (!attribute.startsWith("android:")) return AttrInfo.Indeterminate
        val md = layout()
        if (!md.isWidgetTag(tag)) return AttrInfo.Indeterminate
        // A layout_* param's validity depends on the parent's styleable, so only judge it under a known parent.
        if (attribute.removePrefix("android:").startsWith("layout_") &&
            (parentTag == null || !md.isWidgetTag(parentTag))
        ) return AttrInfo.Indeterminate

        val spec = md.attribute(tag, parentTag, attribute)
            ?: return AttrInfo.NotAllowed // the framework set for this widget is complete → unknown = wrong
        return recognized(spec)
    }

    /** Manifest/drawable flavor: curated catalogs are incomplete, so an unrecognized attribute is left alone. */
    private fun catalogInfo(spec: AttributeSpec?): AttrInfo =
        if (spec == null) AttrInfo.Indeterminate else recognized(spec)

    private fun recognized(spec: AttributeSpec): AttrInfo {
        val closed = closedValues(spec)
        return AttrInfo.Recognized(closed?.first, closed?.second ?: false)
    }

    /** The closed literal value set for [spec] (values + isFlag), or null when its value is free-form. An
     *  attribute that also accepts a reference/dimension/color/string/etc. ([AttributeSpec.resourceTypes] is
     *  non-empty) is free-form and not value-checked. */
    private fun closedValues(spec: AttributeSpec): Pair<Set<String>, Boolean>? {
        if (spec.resourceTypes.isNotEmpty()) return null
        return when {
            spec.flags.isNotEmpty() && spec.enumValues.isEmpty() && !spec.boolean -> spec.flags.toSet() to true
            spec.enumValues.isNotEmpty() && spec.flags.isEmpty() && !spec.boolean -> spec.enumValues.toSet() to false
            spec.boolean && spec.enumValues.isEmpty() && spec.flags.isEmpty() -> setOf("true", "false") to false
            else -> null
        }
    }
}
