package dev.ide.android.support.resources

/**
 * The supported Android resource types — a focused subset of `com.android.ide.common.resources.ResourceType`
 * (the type set aapt2/AGP use), defined here so the resource model is stdlib-only and runs identically
 * on desktop and ART. [rClass] is the nested class name under `R` (e.g. `R.string`); the companion maps
 * resource folder names and `values` XML element tags onto a type.
 */
enum class ResourceType(val rClass: String) {
    ANIM("anim"),
    ANIMATOR("animator"),
    ARRAY("array"),
    ATTR("attr"),
    BOOL("bool"),
    COLOR("color"),
    DIMEN("dimen"),
    DRAWABLE("drawable"),
    FONT("font"),
    FRACTION("fraction"),
    ID("id"),
    INTEGER("integer"),
    INTERPOLATOR("interpolator"),
    LAYOUT("layout"),
    MENU("menu"),
    MIPMAP("mipmap"),
    NAVIGATION("navigation"),
    PLURALS("plurals"),
    RAW("raw"),
    STRING("string"),
    STYLE("style"),
    STYLEABLE("styleable"),
    TRANSITION("transition"),
    XML("xml");

    companion object {
        private val byRClass = entries.associateBy { it.rClass }

        /** Resource types whose entries are files under a `res/<type>[-quals]/` folder. */
        private val FILE_FOLDERS = mapOf(
            "anim" to ANIM, "animator" to ANIMATOR, "color" to COLOR, "drawable" to DRAWABLE,
            "font" to FONT, "interpolator" to INTERPOLATOR, "layout" to LAYOUT, "menu" to MENU,
            "mipmap" to MIPMAP, "navigation" to NAVIGATION, "raw" to RAW, "transition" to TRANSITION,
            "xml" to XML,
        )

        /** `values` XML element tags → type (array variants collapse to [ARRAY]). */
        private val VALUE_TAGS = mapOf(
            "string" to STRING, "color" to COLOR, "dimen" to DIMEN, "bool" to BOOL, "integer" to INTEGER,
            "fraction" to FRACTION, "style" to STYLE, "plurals" to PLURALS, "attr" to ATTR, "id" to ID,
            "drawable" to DRAWABLE, "declare-styleable" to STYLEABLE,
            "string-array" to ARRAY, "integer-array" to ARRAY, "array" to ARRAY,
        )

        /** The R nested-class name (or an `<item type="…">` value) → type. */
        fun byRClass(name: String): ResourceType? = byRClass[name]

        /** A file-based resource folder name (qualifiers already stripped) → type; `values` returns null. */
        fun fromFolder(folder: String): ResourceType? = FILE_FOLDERS[folder]

        /** A `values` element tag → type; `<item>` is resolved separately via its `type` attribute. */
        fun fromValueTag(tag: String): ResourceType? = VALUE_TAGS[tag]
    }
}
