package dev.ide.ui.editor.blocks

/*
 * Compose `Modifier` chains, made legible. A chain such as `Modifier.width(100.dp).background(Color.Red)` is
 * read into its links, drawn with a glyph per link, and edited as a list (reorder, remove, add) with a
 * schematic of the box it describes. Values the block view recognizes (a `dp` number, a named or hex color,
 * a corner shape) are shown as what they are rather than as code.
 */

/** A small mark for what a modifier (or a recognized value) does. */
enum class Glyph {
    Modifier, Width, Height, Size, FillWidth, FillHeight, FillSize, Padding, Offset, Background, Border, Clip,
    Shadow, Alpha, Click, Scroll, Weight, Align, Transform, Generic,
    ShapeRounded, ShapeCut, ShapeCircle, ShapeRect, Arrange, Typography, Icon,
}

fun glyphFor(modifier: String): Glyph = when (modifier) {
    "width", "requiredWidth", "widthIn", "defaultMinSize" -> Glyph.Width
    "height", "requiredHeight", "heightIn" -> Glyph.Height
    "size", "requiredSize", "sizeIn", "wrapContentSize", "wrapContentWidth", "wrapContentHeight", "aspectRatio" -> Glyph.Size
    "fillMaxWidth" -> Glyph.FillWidth
    "fillMaxHeight" -> Glyph.FillHeight
    "fillMaxSize" -> Glyph.FillSize
    "padding", "safeDrawingPadding", "systemBarsPadding", "statusBarsPadding", "navigationBarsPadding", "imePadding" -> Glyph.Padding
    "offset", "absoluteOffset" -> Glyph.Offset
    "background" -> Glyph.Background
    "border" -> Glyph.Border
    "clip", "clipToBounds" -> Glyph.Clip
    "shadow" -> Glyph.Shadow
    "alpha" -> Glyph.Alpha
    "clickable", "combinedClickable", "toggleable", "selectable", "pointerInput", "draggable" -> Glyph.Click
    "verticalScroll", "horizontalScroll", "scrollable", "nestedScroll" -> Glyph.Scroll
    "weight" -> Glyph.Weight
    "align", "wrapContentAlignment" -> Glyph.Align
    "rotate", "scale", "graphicsLayer", "zIndex" -> Glyph.Transform
    else -> Glyph.Generic
}

/** The corner shape a value spells, if it is one the block view draws. */
fun shapeGlyphOf(text: String): Glyph? {
    val t = text.trim()
    return when {
        t.startsWith("RoundedCornerShape") -> Glyph.ShapeRounded
        t.startsWith("CutCornerShape") -> Glyph.ShapeCut
        t == "CircleShape" || t.endsWith(".CircleShape") -> Glyph.ShapeCircle
        t == "RectangleShape" || t.endsWith(".RectangleShape") -> Glyph.ShapeRect
        else -> null
    }
}

private val NAMED_COLORS = mapOf(
    "Black" to 0xFF000000, "DarkGray" to 0xFF444444, "Gray" to 0xFF888888, "LightGray" to 0xFFCCCCCC,
    "White" to 0xFFFFFFFF, "Red" to 0xFFFF0000, "Green" to 0xFF00FF00, "Blue" to 0xFF0000FF,
    "Yellow" to 0xFFFFFF00, "Cyan" to 0xFF00FFFF, "Magenta" to 0xFFFF00FF, "Transparent" to 0x00000000L,
)

/** The ARGB a value spells (`Color.Red`, `Color(0xFF6200EE)`, `Color.Red.copy(...)`), or null. */
fun colorOf(text: String): Long? {
    val t = text.trim()
    Regex("^(?:androidx\\.compose\\.ui\\.graphics\\.)?Color\\.(\\w+)").find(t)?.let { m -> NAMED_COLORS[m.groupValues[1]]?.let { return it } }
    Regex("^Color\\(\\s*0x([0-9A-Fa-f]{6,8})\\s*\\)").find(t)?.let { m ->
        val hex = m.groupValues[1]
        val v = hex.toLong(16)
        return if (hex.length == 6) 0xFF000000 or v else v
    }
    return null
}

// ---------------------------------------------------------------------------
// The chain as data.
// ---------------------------------------------------------------------------

/** One link: `name(args) { lambda }`; [args] and [lambda] are the source inside the brackets, or null. */
data class ModifierLink(val name: String, val args: String?, val lambda: String? = null) {
    fun code(): String = buildString {
        append('.').append(name)
        if (args != null || lambda == null) append('(').append(args ?: "").append(')')
        if (lambda != null) append(" {").append(lambda).append('}')
    }
}

/**
 * A chain: its receiver (`Modifier`, or a `modifier` parameter) and links. [multiline] keeps one link per
 * line; [firstInline] keeps the first link on the receiver's line (`Modifier.width(..)` then the rest below).
 */
data class ModifierChain(val base: String, val links: List<ModifierLink>, val multiline: Boolean, val indent: String, val firstInline: Boolean = false) {
    fun code(): String = when {
        !multiline -> base + links.joinToString("") { it.code() }
        firstInline && links.isNotEmpty() -> base + links.first().code() + links.drop(1).joinToString("") { "\n" + indent + it.code() }
        else -> base + links.joinToString("") { "\n" + indent + it.code() }
    }
}

/**
 * Read [text] as a modifier chain, or null when it is not a plain one (a receiver other than `Modifier` or
 * `modifier`, an operator, unbalanced brackets). [indent] is the indent new lines take.
 */
fun parseModifierChain(text: String, indent: String = "    "): ModifierChain? {
    val t = text.trim()
    val base = Regex("^(Modifier|modifier)\\b").find(t)?.value ?: return null
    var i = base.length
    val links = ArrayList<ModifierLink>()
    var multiline = false
    val firstInline = t.getOrNull(i) == '.'
    fun skipWs() { while (i < t.length && t[i].isWhitespace()) { if (t[i] == '\n') multiline = true; i++ } }
    fun balanced(open: Char, close: Char): String? {
        if (i >= t.length || t[i] != open) return null
        var depth = 0
        val start = i + 1
        var inString = false
        while (i < t.length) {
            val c = t[i]
            if (c == '"' && (i == 0 || t[i - 1] != '\\')) inString = !inString
            if (!inString) {
                if (c == open) depth++
                if (c == close) { depth--; if (depth == 0) { i++; return t.substring(start, i - 1) } }
            }
            i++
        }
        return null
    }
    while (true) {
        skipWs()
        if (i >= t.length) break
        if (t[i] != '.') return null
        i++
        val nameStart = i
        while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_')) i++
        val name = t.substring(nameStart, i)
        if (name.isEmpty()) return null
        skipWs()
        val args = if (i < t.length && t[i] == '(') balanced('(', ')') ?: return null else null
        val save = i
        skipWs()
        val lambda = if (i < t.length && t[i] == '{') balanced('{', '}') ?: return null else { i = save; null }
        links += ModifierLink(name, args, lambda)
    }
    if (links.isEmpty()) return null
    return ModifierChain(base, links, multiline, indent, firstInline)
}

/** Modifiers the editor's Add menu offers, grouped, each with the code a new link starts as. */
val MODIFIER_PRESETS: List<Pair<String, List<ModifierLink>>> = listOf(
    "Size" to listOf(
        ModifierLink("fillMaxWidth", ""), ModifierLink("fillMaxHeight", ""), ModifierLink("fillMaxSize", ""),
        ModifierLink("width", "100.dp"), ModifierLink("height", "100.dp"), ModifierLink("size", "48.dp"),
        ModifierLink("wrapContentSize", ""), ModifierLink("weight", "1f"),
    ),
    "Spacing" to listOf(
        ModifierLink("padding", "16.dp"), ModifierLink("padding", "horizontal = 16.dp, vertical = 8.dp"), ModifierLink("offset", "x = 0.dp, y = 0.dp"),
    ),
    "Drawing" to listOf(
        ModifierLink("background", "Color.LightGray"), ModifierLink("background", "Color.LightGray, RoundedCornerShape(8.dp)"),
        ModifierLink("border", "1.dp, Color.Gray, RoundedCornerShape(8.dp)"), ModifierLink("clip", "RoundedCornerShape(8.dp)"),
        ModifierLink("shadow", "4.dp, RoundedCornerShape(8.dp)"), ModifierLink("alpha", "0.5f"),
    ),
    "Behaviour" to listOf(
        ModifierLink("clickable", null, " "), ModifierLink("verticalScroll", "rememberScrollState()"), ModifierLink("horizontalScroll", "rememberScrollState()"),
    ),
)

// ---------------------------------------------------------------------------
// The schematic: what box the chain describes, approximately.
// ---------------------------------------------------------------------------

/** A drawing step of the schematic, in dp, inside a box of [Schematic.width] x [Schematic.height]. */
sealed interface SchematicOp {
    data class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val argb: Long, val shape: Glyph, val radius: Float) : SchematicOp
    data class Stroke(val x: Float, val y: Float, val w: Float, val h: Float, val argb: Long, val width: Float, val shape: Glyph, val radius: Float) : SchematicOp
    data class Inset(val x: Float, val y: Float, val w: Float, val h: Float) : SchematicOp
    data class Content(val x: Float, val y: Float, val w: Float, val h: Float) : SchematicOp
}

class Schematic(val width: Float, val height: Float, val fillsWidth: Boolean, val fillsHeight: Boolean, val ops: List<SchematicOp>, val alpha: Float)

private fun dpOf(text: String?): Float? = text?.trim()?.let { Regex("^(-?\\d+(?:\\.\\d+)?)\\s*\\.?\\s*dp$").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

/** A comma-split argument list into positional values and `name = value` pairs. */
private fun argsOf(args: String?): Pair<List<String>, Map<String, String>> {
    if (args.isNullOrBlank()) return emptyList<String>() to emptyMap()
    val positional = ArrayList<String>()
    val named = HashMap<String, String>()
    for (a in splitTopLevel(args)) {
        val eq = Regex("^\\s*(\\w+)\\s*=(?!=)\\s*(.*)$").find(a)
        if (eq != null) named[eq.groupValues[1]] = eq.groupValues[2].trim() else positional += a.trim()
    }
    return positional to named
}

private fun radiusOf(shape: String?): Float = shape?.let { Regex("\\((\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f

/**
 * Lay the chain out as Compose would, approximately: modifiers apply outside-in, so a size sets the box, a
 * padding insets what follows, a background or border paints the current box in the current clip shape, and
 * what is left in the middle is the content. Unknown links are skipped. Sizes without a value use a default.
 */
fun schematicOf(chain: ModifierChain, defaultW: Float = 160f, defaultH: Float = 96f): Schematic {
    var w: Float? = null
    var h: Float? = null
    var fillW = false
    var fillH = false
    for (l in chain.links) {
        val (pos, named) = argsOf(l.args)
        when (l.name) {
            "width", "requiredWidth" -> if (w == null) w = dpOf(pos.firstOrNull())
            "height", "requiredHeight" -> if (h == null) h = dpOf(pos.firstOrNull())
            "size", "requiredSize" -> {
                if (w == null) w = dpOf(pos.firstOrNull() ?: named["width"])
                if (h == null) h = dpOf(pos.getOrNull(1) ?: pos.firstOrNull() ?: named["height"])
            }
            "fillMaxWidth" -> fillW = true
            "fillMaxHeight" -> fillH = true
            "fillMaxSize" -> { fillW = true; fillH = true }
        }
    }
    val bw = (w ?: defaultW).coerceIn(24f, 400f)
    val bh = (h ?: defaultH).coerceIn(24f, 300f)
    var x = 0f
    var y = 0f
    var cw = bw
    var ch = bh
    var shape = Glyph.ShapeRect
    var radius = 0f
    var alpha = 1f
    val ops = ArrayList<SchematicOp>()
    for (l in chain.links) {
        val (pos, named) = argsOf(l.args)
        when (l.name) {
            "padding" -> {
                val all = dpOf(pos.firstOrNull() ?: named["all"]) ?: 0f
                val hz = dpOf(named["horizontal"]) ?: all
                val vt = dpOf(named["vertical"]) ?: all
                val s = dpOf(named["start"]) ?: hz
                val e = dpOf(named["end"]) ?: hz
                val t = dpOf(named["top"]) ?: vt
                val b = dpOf(named["bottom"]) ?: vt
                ops += SchematicOp.Inset(x, y, cw, ch)
                x += s; y += t; cw = (cw - s - e).coerceAtLeast(4f); ch = (ch - t - b).coerceAtLeast(4f)
            }
            "clip" -> { shape = shapeGlyphOf(pos.firstOrNull() ?: "") ?: shape; radius = radiusOf(pos.firstOrNull()) }
            "background" -> {
                val color = colorOf(pos.firstOrNull() ?: named["color"] ?: "") ?: 0xFF9E9E9E
                val sh = pos.getOrNull(1) ?: named["shape"]
                ops += SchematicOp.Fill(x, y, cw, ch, color, sh?.let(::shapeGlyphOf) ?: shape, sh?.let(::radiusOf) ?: radius)
            }
            "border" -> {
                val bwidth = dpOf(pos.firstOrNull() ?: named["width"]) ?: 1f
                val color = colorOf(pos.getOrNull(1) ?: named["color"] ?: "") ?: 0xFF9E9E9E
                val sh = pos.getOrNull(2) ?: named["shape"]
                ops += SchematicOp.Stroke(x, y, cw, ch, color, bwidth, sh?.let(::shapeGlyphOf) ?: shape, sh?.let(::radiusOf) ?: radius)
            }
            "alpha" -> alpha = pos.firstOrNull()?.removeSuffix("f")?.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: alpha
            "offset" -> { x += dpOf(named["x"] ?: pos.firstOrNull()) ?: 0f; y += dpOf(named["y"] ?: pos.getOrNull(1)) ?: 0f }
        }
    }
    ops += SchematicOp.Content(x, y, cw, ch)
    return Schematic(bw, bh, fillW, fillH, ops, alpha)
}

// ---------------------------------------------------------------------------
// Well-known Compose values, shown short.
// ---------------------------------------------------------------------------

/** A value's short display: its last name, with a glyph or a color swatch for what kind of value it is. */
class ShortValue(val text: String, val glyph: Glyph? = null, val argb: Long? = null)

// Approximate Material 3 baseline colors, for a swatch on `MaterialTheme.colorScheme.x`.
private val SCHEME_COLORS = mapOf(
    "primary" to 0xFF6750A4, "onPrimary" to 0xFFFFFFFF, "primaryContainer" to 0xFFEADDFF, "onPrimaryContainer" to 0xFF21005D,
    "secondary" to 0xFF625B71, "onSecondary" to 0xFFFFFFFF, "secondaryContainer" to 0xFFE8DEF8, "onSecondaryContainer" to 0xFF1D192B,
    "tertiary" to 0xFF7D5260, "onTertiary" to 0xFFFFFFFF, "tertiaryContainer" to 0xFFFFD8E4,
    "background" to 0xFFFFFBFE, "onBackground" to 0xFF1C1B1F, "surface" to 0xFFFFFBFE, "onSurface" to 0xFF1C1B1F,
    "surfaceVariant" to 0xFFE7E0EC, "onSurfaceVariant" to 0xFF49454F, "outline" to 0xFF79747E, "outlineVariant" to 0xFFCAC4D0,
    "error" to 0xFFB3261E, "onError" to 0xFFFFFFFF, "errorContainer" to 0xFFF9DEDC, "inverseSurface" to 0xFF313033,
)

/**
 * The short form of a dotted Compose value, or null when it is not one the block view recognizes:
 * `Alignment.CenterHorizontally`, `Arrangement.Center`, `MaterialTheme.colorScheme.background`,
 * `MaterialTheme.typography.headlineMedium`, `FontWeight.Bold`, `TextAlign.Center`, `Color.Red`, `Icons.Default.Add`.
 */
fun shortValue(text: String): ShortValue? {
    val t = text.trim()
    val last = t.substringAfterLast('.')
    if (last == t) return null
    val holder = t.substringBeforeLast('.').removeSuffix(".Companion")
    return when {
        holder == "Alignment" || holder == "TextAlign" || holder == "Alignment.Horizontal" || holder == "Alignment.Vertical" -> ShortValue(last, Glyph.Align)
        holder == "Arrangement" || holder == "Arrangement.Horizontal" || holder == "Arrangement.Vertical" -> ShortValue(last, Glyph.Arrange)
        holder.endsWith("colorScheme") -> ShortValue(last, argb = SCHEME_COLORS[last] ?: 0xFF9E9E9E)
        holder.endsWith("typography") || holder == "FontWeight" || holder == "FontStyle" || holder == "FontFamily" -> ShortValue(last, Glyph.Typography)
        holder == "Color" -> colorOf(t)?.let { ShortValue(last, argb = it) }
        holder.startsWith("Icons.") -> ShortValue(last, Glyph.Icon)
        holder == "ContentScale" -> ShortValue(last, Glyph.Size)
        holder == "TextOverflow" -> ShortValue(last, Glyph.Typography)
        else -> null
    }
}
