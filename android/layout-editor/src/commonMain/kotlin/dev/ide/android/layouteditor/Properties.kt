package dev.ide.android.layouteditor

import dev.ide.android.layout.LayoutAttributeInfo
import dev.ide.android.layout.LayoutElement
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/*
 * The property strip's model: which properties a view gets as tiles, and how a tile reads its value. A property
 * is a user-level idea ("margin", "image") that may span several attributes (`layout_margin` and its four
 * sides; `app:srcCompat` or `android:src`), which is what lets one tile open one visual editor.
 */

/** How a property is edited. */
internal enum class PropertyEditor {
    ID, SIZE, MARGIN, PADDING, GRAVITY, WEIGHT, IMAGE, SCALE_TYPE, COLOR, TEXT, TEXT_SIZE, CHOICE, BOOLEAN, ALPHA, PLAIN,
}

/**
 * One tile. [attributes] are the attributes it edits; the first one the view accepts is the one it writes (so
 * `image` writes `app:srcCompat` on an AppCompat view and `android:src` otherwise).
 */
internal class PropertySpec(
    val key: String,
    val label: StringResource,
    val glyph: Char,
    val attributes: List<String>,
    val editor: PropertyEditor,
)

/** A margin or padding: the shorthand that sets all four sides, and the sides it can be split into. */
internal class BoxAttributes(
    val shorthand: String,
    val horizontal: String,
    val vertical: String,
    val start: String,
    val top: String,
    val end: String,
    val bottom: String,
) {
    val all: List<String> get() = listOf(shorthand, horizontal, vertical, start, top, end, bottom)
}

internal val BOX_MARGIN = BoxAttributes(
    "android:layout_margin", "android:layout_marginHorizontal", "android:layout_marginVertical",
    "android:layout_marginStart", "android:layout_marginTop", "android:layout_marginEnd", "android:layout_marginBottom",
)
internal val BOX_PADDING = BoxAttributes(
    "android:padding", "android:paddingHorizontal", "android:paddingVertical",
    "android:paddingStart", "android:paddingTop", "android:paddingEnd", "android:paddingBottom",
)

private val ID = PropertySpec("id", Res.string.le_prop_id, Sym.tag, listOf("android:id"), PropertyEditor.ID)
private val SIZE = PropertySpec("size", Res.string.le_prop_size, Sym.aspectRatio, listOf("android:layout_width", "android:layout_height"), PropertyEditor.SIZE)
private val MARGIN = PropertySpec("margin", Res.string.le_prop_margin, Sym.margin, BOX_MARGIN.all, PropertyEditor.MARGIN)
private val PADDING = PropertySpec("padding", Res.string.le_prop_padding, Sym.padding, BOX_PADDING.all, PropertyEditor.PADDING)
private val LAYOUT_GRAVITY = PropertySpec("layoutGravity", Res.string.le_prop_layout_gravity, Sym.controlCamera, listOf("android:layout_gravity"), PropertyEditor.GRAVITY)
private val GRAVITY = PropertySpec("gravity", Res.string.le_prop_gravity, Sym.formatAlignCenter, listOf("android:gravity"), PropertyEditor.GRAVITY)
private val WEIGHT = PropertySpec("weight", Res.string.le_prop_weight, Sym.balance, listOf("android:layout_weight"), PropertyEditor.WEIGHT)
private val TEXT = PropertySpec("text", Res.string.le_prop_text, Sym.title, listOf("android:text"), PropertyEditor.TEXT)
private val HINT = PropertySpec("hint", Res.string.le_prop_hint, Sym.shortText, listOf("android:hint"), PropertyEditor.TEXT)
private val TEXT_SIZE = PropertySpec("textSize", Res.string.le_prop_text_size, Sym.formatSize, listOf("android:textSize"), PropertyEditor.TEXT_SIZE)
private val TEXT_COLOR = PropertySpec("textColor", Res.string.le_prop_text_color, Sym.formatColorText, listOf("android:textColor"), PropertyEditor.COLOR)
private val TEXT_STYLE = PropertySpec("textStyle", Res.string.le_prop_text_style, Sym.formatBold, listOf("android:textStyle"), PropertyEditor.CHOICE)
private val IMAGE = PropertySpec("image", Res.string.le_prop_image, Sym.image, listOf("app:srcCompat", "android:src"), PropertyEditor.IMAGE)
private val SCALE_TYPE = PropertySpec("scaleType", Res.string.le_prop_scale_type, Sym.fitScreen, listOf("android:scaleType"), PropertyEditor.SCALE_TYPE)
private val TINT = PropertySpec("tint", Res.string.le_prop_tint, Sym.colorize, listOf("app:tint", "android:tint"), PropertyEditor.COLOR)
private val BACKGROUND = PropertySpec("background", Res.string.le_prop_background, Sym.formatColorFill, listOf("android:background"), PropertyEditor.COLOR)
private val BACKGROUND_TINT = PropertySpec("backgroundTint", Res.string.le_prop_background_tint, Sym.formatColorFill, listOf("app:backgroundTint", "android:backgroundTint"), PropertyEditor.COLOR)
private val ORIENTATION = PropertySpec("orientation", Res.string.le_prop_orientation, Sym.swapHoriz, listOf("android:orientation"), PropertyEditor.CHOICE)
private val VISIBILITY = PropertySpec("visibility", Res.string.le_prop_visibility, Sym.visibility, listOf("android:visibility"), PropertyEditor.CHOICE)
private val ENABLED = PropertySpec("enabled", Res.string.le_prop_enabled, Sym.toggleOn, listOf("android:enabled"), PropertyEditor.BOOLEAN)
private val CHECKED = PropertySpec("checked", Res.string.le_prop_checked, Sym.checkBox, listOf("android:checked"), PropertyEditor.BOOLEAN)
private val INPUT_TYPE = PropertySpec("inputType", Res.string.le_prop_input_type, Sym.keyboard, listOf("android:inputType"), PropertyEditor.CHOICE)
private val ALPHA = PropertySpec("alpha", Res.string.le_prop_alpha, Sym.opacity, listOf("android:alpha"), PropertyEditor.ALPHA)
private val DESCRIPTION = PropertySpec("contentDescription", Res.string.le_prop_description, Sym.accessibilityNew, listOf("android:contentDescription"), PropertyEditor.TEXT)

/** Every property a tile can be, for the Recent tab to look keys up in. */
internal val ALL_PROPERTIES: List<PropertySpec> = listOf(
    ID, SIZE, MARGIN, PADDING, LAYOUT_GRAVITY, GRAVITY, WEIGHT, TEXT, HINT, TEXT_SIZE, TEXT_COLOR, TEXT_STYLE,
    IMAGE, SCALE_TYPE, TINT, BACKGROUND, BACKGROUND_TINT, ORIENTATION, VISIBILITY, ENABLED, CHECKED, INPUT_TYPE,
    ALPHA, DESCRIPTION,
)

/**
 * The Basic tab for a view of [tag]: what that kind of view is about first, then layout, then appearance. Only
 * properties the view accepts in its parent survive ([accepts]), so a LinearLayout child gets `weight` and a
 * FrameLayout child does not.
 */
internal fun basicPropertiesFor(tag: String, accepts: (String) -> Boolean): List<PropertySpec> {
    val n = tag.substringAfterLast('.')
    val kind = when {
        n.endsWith("TextInputLayout") -> listOf(HINT)
        n.endsWith("EditText") -> listOf(HINT, TEXT, INPUT_TYPE, TEXT_SIZE, TEXT_COLOR)
        n.contains("Switch") || n.endsWith("CheckBox") || n.endsWith("RadioButton") -> listOf(TEXT, CHECKED, TEXT_COLOR)
        n.endsWith("ImageButton") || n.endsWith("FloatingActionButton") -> listOf(IMAGE, TINT, BACKGROUND_TINT, DESCRIPTION)
        n.endsWith("Button") || n.endsWith("Chip") -> listOf(TEXT, TEXT_SIZE, BACKGROUND_TINT, TEXT_COLOR, ENABLED)
        n.endsWith("ImageView") -> listOf(IMAGE, SCALE_TYPE, TINT, DESCRIPTION)
        n.endsWith("TextView") -> listOf(TEXT, TEXT_SIZE, TEXT_COLOR, TEXT_STYLE, GRAVITY)
        n == "LinearLayout" || n == "LinearLayoutCompat" -> listOf(ORIENTATION, GRAVITY)
        n == "FrameLayout" || n.endsWith("Layout") -> listOf(GRAVITY)
        else -> emptyList()
    }
    val layout = listOf(SIZE, MARGIN, LAYOUT_GRAVITY, WEIGHT)
    val look = listOf(PADDING, BACKGROUND, VISIBILITY, ALPHA, ID)
    return (kind + layout + look).distinctBy { it.key }.filter { p -> p.attributes.any(accepts) }
}

/** The attribute [spec] writes on a view that accepts [accepts]: its first accepted attribute. */
internal fun PropertySpec.writeAttribute(accepts: (String) -> Boolean): String = attributes.firstOrNull(accepts) ?: attributes.first()

/** The four sides of a box as written on [element], each resolved the way the framework does: side, then axis, then all. */
internal data class BoxValues(val start: String?, val top: String?, val end: String?, val bottom: String?) {
    val uniform: String? get() = start.takeIf { it != null && it == top && it == end && it == bottom }
    val isEmpty: Boolean get() = start == null && top == null && end == null && bottom == null
}

internal fun readBox(element: LayoutElement, box: BoxAttributes): BoxValues {
    fun v(name: String) = element.attribute(name)?.value?.takeIf { it.isNotBlank() }
    val all = v(box.shorthand)
    val h = v(box.horizontal) ?: all
    val vert = v(box.vertical) ?: all
    return BoxValues(
        start = v(box.start) ?: v(box.start.replace("Start", "Left")) ?: h,
        top = v(box.top) ?: vert,
        end = v(box.end) ?: v(box.end.replace("End", "Right")) ?: h,
        bottom = v(box.bottom) ?: vert,
    )
}

/**
 * The attribute writes that make [element]'s box equal [next]: the shorthand when all four sides agree (and every
 * longhand removed), else the four sides (and the shorthand forms removed). Null means remove. Only attributes
 * that change are returned; a pair `(name, null)` is a removal.
 */
internal fun boxWrites(element: LayoutElement, box: BoxAttributes, next: BoxValues): List<Pair<String, String?>> {
    val target = LinkedHashMap<String, String?>()
    val uniform = next.uniform
    if (next.isEmpty) {
        for (a in box.all) target[a] = null
    } else if (uniform != null) {
        target[box.shorthand] = uniform
        for (a in box.all - box.shorthand) target[a] = null
    } else {
        target[box.shorthand] = null
        target[box.horizontal] = null
        target[box.vertical] = null
        target[box.start] = next.start
        target[box.top] = next.top
        target[box.end] = next.end
        target[box.bottom] = next.bottom
    }
    return target.filter { (name, value) -> element.attribute(name)?.value != value }.map { it.key to it.value }
}

/** `16dp` → 16; null for anything that is not a plain dp/sp/px number (a `@dimen/` reference). */
internal fun dpNumber(value: String?): Float? =
    value?.trim()?.let { Regex("""^(-?\d+(?:\.\d+)?)(dp|dip|sp|px)?$""").find(it) }?.groupValues?.get(1)?.toFloatOrNull()

/** A number as a dimension with no trailing `.0`. */
internal fun dp(n: Float, unit: String = "dp"): String =
    (if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()) + unit

/** Gravity as a 3×3 cell: (row, column), each 0..2, or null when the value is not one cell (e.g. `fill`). */
internal fun gravityCell(value: String?): Pair<Int, Int>? {
    if (value.isNullOrBlank()) return null
    val flags = value.split('|').map { it.trim() }.toSet()
    if ("center" in flags) {
        return Pair(
            when { "top" in flags -> 0; "bottom" in flags -> 2; else -> 1 },
            when { "start" in flags || "left" in flags -> 0; "end" in flags || "right" in flags -> 2; else -> 1 },
        )
    }
    val row = when {
        "top" in flags -> 0
        "bottom" in flags -> 2
        "center_vertical" in flags -> 1
        else -> 0
    }
    val col = when {
        "start" in flags || "left" in flags -> 0
        "end" in flags || "right" in flags -> 2
        "center_horizontal" in flags -> 1
        else -> 0
    }
    return row to col
}

/** The gravity value for a 3×3 cell, in the shortest form the framework reads the same. */
internal fun gravityFor(row: Int, col: Int): String {
    if (row == 1 && col == 1) return "center"
    val v = when (row) { 0 -> "top"; 2 -> "bottom"; else -> "center_vertical" }
    val h = when (col) { 0 -> "start"; 2 -> "end"; else -> "center_horizontal" }
    return "$v|$h"
}

/**
 * A short line saying what a property is set to, for its tile: `wrap × 48dp`, `16dp`, `8 · 16 · 8 · 16`,
 * `center`. Null when it is unset (the tile then shows the property as available rather than set).
 */
internal fun previewOf(spec: PropertySpec, element: LayoutElement, strings: Map<String, String>): String? {
    fun v(name: String) = element.attribute(name)?.value
    fun short(s: String) = when (s) {
        "wrap_content" -> "wrap"
        "match_parent", "fill_parent" -> "match"
        else -> s
    }
    return when (spec.editor) {
        PropertyEditor.SIZE -> {
            val w = v("android:layout_width")
            val h = v("android:layout_height")
            if (w == null && h == null) null else "${w?.let(::short) ?: "–"} × ${h?.let(::short) ?: "–"}"
        }
        PropertyEditor.MARGIN, PropertyEditor.PADDING -> {
            val box = readBox(element, if (spec.editor == PropertyEditor.MARGIN) BOX_MARGIN else BOX_PADDING)
            when {
                box.isEmpty -> null
                box.uniform != null -> box.uniform
                else -> listOf(box.start, box.top, box.end, box.bottom).joinToString(" · ") { s -> s?.let { dpNumber(it)?.let(::dp)?.removeSuffix("dp") ?: it } ?: "0" }
            }
        }
        PropertyEditor.ID -> v("android:id")?.substringAfter('/')
        PropertyEditor.TEXT -> spec.attributes.firstNotNullOfOrNull(::v)?.let { raw ->
            if (raw.startsWith("@string/")) strings[raw.removePrefix("@string/")]?.let { "“$it”" } ?: raw else "“$raw”"
        }
        PropertyEditor.IMAGE, PropertyEditor.COLOR -> spec.attributes.firstNotNullOfOrNull(::v)?.substringAfter('/')
        else -> spec.attributes.firstNotNullOfOrNull(::v)
    }
}

/** Whether [info] describes an attribute whose enum/flags values a choice editor can offer. */
internal fun choicesOf(info: LayoutAttributeInfo?): Pair<List<String>, Boolean> = when {
    info == null -> emptyList<String>() to false
    info.flagValues.isNotEmpty() -> info.flagValues to true
    else -> info.enumValues to false
}
