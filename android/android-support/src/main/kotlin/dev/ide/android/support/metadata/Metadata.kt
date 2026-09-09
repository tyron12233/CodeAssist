package dev.ide.android.support.metadata

/**
 * The source of XML layout completion metadata. The sole implementation is the SDK-derived
 * [AndroidSdkMetadata] (built from the platform `attrs.xml` + `android.jar` hierarchy and bundled as an
 * asset), so completion is exhaustive and hierarchy-correct (e.g. a `RelativeLayout` child gets
 * `layout_below`/`layout_toEndOf`). The interface stays so a future alternate source (or a test double)
 * can slot in.
 */
interface LayoutMetadata {
    /** Element tags valid inside [parentTag] (all known widgets today; content-model awareness is future). */
    fun childTagsFor(parentTag: String?): List<Widget>

    /** Attributes for element [tag] inside parent [parentTag] — own (incl. inherited) + parent layout attrs. */
    fun attributesFor(tag: String?, parentTag: String?): List<AttributeSpec>

    /** A single attribute's spec (for value completion), or null if unknown for this tag. */
    fun attribute(tag: String?, parentTag: String?, attributeName: String?): AttributeSpec?
}

/** A widget usable as an element tag in a layout. [tag] is what the user types (a simple class name). */
data class Widget(val tag: String, val isViewGroup: Boolean, val doc: String? = null)

/**
 * One attribute + what its value accepts: a fixed [enumValues] set, a [flags] (space/`|`-separated) set,
 * whether it takes a [boolean], and which [resourceTypes] a `@type/…` reference may point at. Names are
 * prefixed for completion (`android:…` for framework attrs, `app:…` for custom-view attrs).
 */
data class AttributeSpec(
    val name: String,
    val enumValues: List<String> = emptyList(),
    val flags: List<String> = emptyList(),
    val boolean: Boolean = false,
    val resourceTypes: List<dev.ide.android.support.resources.ResourceType> = emptyList(),
    val doc: String? = null,
)

/** An attribute value format, as declared by `format="…"` in `attrs.xml` (or implied by enum/flag children). */
enum class AttrFormat {
    REFERENCE, STRING, COLOR, DIMENSION, BOOLEAN, INTEGER, FLOAT, FRACTION, ENUM, FLAG;

    companion object {
        fun parse(token: String): AttrFormat? = when (token.trim().lowercase()) {
            "reference" -> REFERENCE
            "string" -> STRING
            "color" -> COLOR
            "dimension" -> DIMENSION
            "boolean" -> BOOLEAN
            "integer" -> INTEGER
            "float" -> FLOAT
            "fraction" -> FRACTION
            "enum" -> ENUM
            "flag" -> FLAG
            else -> null
        }
    }
}

/** One attribute definition gathered from `attrs.xml`: its name (unprefixed), value formats, and enum/flag value names. */
data class AttrEntry(
    val name: String,
    val formats: Set<AttrFormat> = emptySet(),
    val enumValues: List<String> = emptyList(),
    val flagValues: List<String> = emptyList(),
)

/** A `<declare-styleable name="…">` and the attr names it groups (e.g. `TextView` → text, textColor, …). */
data class StyleableEntry(val name: String, val attrs: List<String>)
