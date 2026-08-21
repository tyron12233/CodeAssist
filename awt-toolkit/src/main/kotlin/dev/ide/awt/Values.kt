package dev.ide.awt

/**
 * `java.awt.Color`. Packed as `0xAARRGGBB` in [rgb], the same packing [dev.ide.preview.RPaint] uses, so a
 * color reaches the canvas without conversion.
 */
class Color {
    val red: Int
    val green: Int
    val blue: Int
    val alpha: Int

    constructor(r: Int, g: Int, b: Int) : this(r, g, b, 255)

    constructor(r: Int, g: Int, b: Int, a: Int) {
        red = r and 0xFF
        green = g and 0xFF
        blue = b and 0xFF
        alpha = a and 0xFF
    }

    /** The `new Color(0x2D6CDF)` form: the low 24 bits are RGB and the color is opaque. */
    constructor(rgb: Int) : this((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 255)

    /** `0xAARRGGBB`, ready for a paint. */
    val rgb: Int get() = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun brighter(): Color = Color(scale(red, 1.4), scale(green, 1.4), scale(blue, 1.4), alpha)
    fun darker(): Color = Color(scale(red, 0.7), scale(green, 0.7), scale(blue, 0.7), alpha)

    private fun scale(c: Int, by: Double): Int = (c * by).toInt().coerceIn(0, 255)

    override fun equals(other: Any?): Boolean = other is Color && other.rgb == rgb
    override fun hashCode(): Int = rgb
    override fun toString(): String = "java.awt.Color[r=$red,g=$green,b=$blue]"

    companion object {
        @JvmField val WHITE = Color(255, 255, 255)
        @JvmField val LIGHT_GRAY = Color(192, 192, 192)
        @JvmField val GRAY = Color(128, 128, 128)
        @JvmField val DARK_GRAY = Color(64, 64, 64)
        @JvmField val BLACK = Color(0, 0, 0)
        @JvmField val RED = Color(255, 0, 0)
        @JvmField val PINK = Color(255, 175, 175)
        @JvmField val ORANGE = Color(255, 200, 0)
        @JvmField val YELLOW = Color(255, 255, 0)
        @JvmField val GREEN = Color(0, 255, 0)
        @JvmField val MAGENTA = Color(255, 0, 255)
        @JvmField val CYAN = Color(0, 255, 255)
        @JvmField val BLUE = Color(0, 0, 255)

        // AWT declares each constant twice, upper and lower case (`Color.white` is as common in teaching
        // material as `Color.WHITE`), and a program's bytecode names whichever it used.
        @JvmField val white = WHITE
        @JvmField val lightGray = LIGHT_GRAY
        @JvmField val gray = GRAY
        @JvmField val darkGray = DARK_GRAY
        @JvmField val black = BLACK
        @JvmField val red = RED
        @JvmField val pink = PINK
        @JvmField val orange = ORANGE
        @JvmField val yellow = YELLOW
        @JvmField val green = GREEN
        @JvmField val magenta = MAGENTA
        @JvmField val cyan = CYAN
        @JvmField val blue = BLUE
    }
}

/** `java.awt.Dimension`. Mutable, and its fields are public, because AWT code reads `d.width` directly. */
class Dimension @JvmOverloads constructor(@JvmField var width: Int = 0, @JvmField var height: Int = 0) {
    constructor(d: Dimension) : this(d.width, d.height)

    fun getWidth(): Double = width.toDouble()
    fun getHeight(): Double = height.toDouble()
    fun setSize(w: Int, h: Int) {
        width = w
        height = h
    }

    override fun equals(other: Any?): Boolean = other is Dimension && other.width == width && other.height == height
    override fun hashCode(): Int = width * 31 + height
    override fun toString(): String = "java.awt.Dimension[width=$width,height=$height]"
}

/** `java.awt.Point`. */
class Point @JvmOverloads constructor(@JvmField var x: Int = 0, @JvmField var y: Int = 0) {
    override fun equals(other: Any?): Boolean = other is Point && other.x == x && other.y == y
    override fun hashCode(): Int = x * 31 + y
    override fun toString(): String = "java.awt.Point[x=$x,y=$y]"
}

/** `java.awt.Rectangle`. */
class Rectangle @JvmOverloads constructor(
    @JvmField var x: Int = 0,
    @JvmField var y: Int = 0,
    @JvmField var width: Int = 0,
    @JvmField var height: Int = 0,
) {
    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height

    override fun equals(other: Any?): Boolean =
        other is Rectangle && other.x == x && other.y == y && other.width == width && other.height == height

    override fun hashCode(): Int = ((x * 31 + y) * 31 + width) * 31 + height
    override fun toString(): String = "java.awt.Rectangle[x=$x,y=$y,width=$width,height=$height]"
}

/** `java.awt.Insets`: the padding a container reserves inside its own bounds. */
class Insets(
    @JvmField var top: Int,
    @JvmField var left: Int,
    @JvmField var bottom: Int,
    @JvmField var right: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is Insets && other.top == top && other.left == left && other.bottom == bottom && other.right == right

    override fun hashCode(): Int = ((top * 31 + left) * 31 + bottom) * 31 + right
    override fun toString(): String = "java.awt.Insets[top=$top,left=$left,bottom=$bottom,right=$right]"
}

/**
 * `java.awt.Font`. Only the three attributes a paint can carry are modelled: the family name is advisory (the
 * backend resolves the actual typeface), [style] drives bold/italic, and [size] is the point size, used as
 * pixels the way the preview's text rendering does.
 */
class Font(val name: String?, val style: Int, val size: Int) {
    val isBold: Boolean get() = (style and BOLD) != 0
    val isItalic: Boolean get() = (style and ITALIC) != 0
    val isPlain: Boolean get() = style == PLAIN

    /** AWT's `getFamily`; `getName` comes from the [name] property itself. */
    fun getFamily(): String = name ?: "SansSerif"

    /** `font.deriveFont(18f)`: the same family and style at a new size. */
    fun deriveFont(newSize: Float): Font = Font(name, style, newSize.toInt())

    /** `font.deriveFont(Font.BOLD)`: the same family and size in a new style. */
    fun deriveFont(newStyle: Int): Font = Font(name, newStyle, size)

    override fun equals(other: Any?): Boolean =
        other is Font && other.name == name && other.style == style && other.size == size

    override fun hashCode(): Int = ((name?.hashCode() ?: 0) * 31 + style) * 31 + size
    override fun toString(): String = "java.awt.Font[family=${getFamily()},style=$style,size=$size]"

    companion object {
        @JvmField val PLAIN = 0
        @JvmField val BOLD = 1
        @JvmField val ITALIC = 2
    }
}

/**
 * `java.awt.FontMetrics`. Measurement is the backend's job (an [dev.ide.preview.RGraphics] knows the real
 * typeface), so this is a thin carrier over what it reported.
 */
class FontMetrics internal constructor(
    val font: Font,
    private val height: Int,
    private val ascent: Int,
    private val measure: (String) -> Int,
) {
    fun getHeight(): Int = height
    fun getAscent(): Int = ascent
    fun getDescent(): Int = height - ascent
    fun getLeading(): Int = 0
    fun stringWidth(text: String?): Int = if (text.isNullOrEmpty()) 0 else measure(text)
    fun charWidth(c: Char): Int = measure(c.toString())
}

/**
 * `java.awt.RenderingHints`. The keys and values are identity tokens, as in AWT; only antialiasing is acted
 * on, and every other hint is accepted and ignored, because the canvas underneath decides its own quality.
 */
class RenderingHints private constructor() {
    /** `RenderingHints.Key`, an opaque token. */
    class Key internal constructor(private val label: String) {
        override fun toString(): String = label
    }

    companion object {
        @JvmField val KEY_ANTIALIASING = Key("Antialiasing")
        @JvmField val KEY_TEXT_ANTIALIASING = Key("Text antialiasing")
        @JvmField val KEY_RENDERING = Key("Rendering")
        @JvmField val VALUE_ANTIALIAS_ON: Any = "Antialias on"
        @JvmField val VALUE_ANTIALIAS_OFF: Any = "Antialias off"
        @JvmField val VALUE_TEXT_ANTIALIAS_ON: Any = "Text antialias on"
        @JvmField val VALUE_TEXT_ANTIALIAS_OFF: Any = "Text antialias off"
        @JvmField val VALUE_RENDER_QUALITY: Any = "Render quality"
        @JvmField val VALUE_RENDER_SPEED: Any = "Render speed"
    }
}

/** `java.awt.BasicStroke`: only the line width reaches the canvas. */
class BasicStroke @JvmOverloads constructor(val lineWidth: Float = 1f)
