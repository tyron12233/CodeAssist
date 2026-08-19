package dev.ide.swing.border

import dev.ide.awt.Color
import dev.ide.awt.Component
import dev.ide.awt.Font
import dev.ide.awt.Graphics
import dev.ide.awt.Insets

/**
 * `javax.swing.border.Border`: the frame a component draws around its own content, and the space it reserves
 * for it. A component's insets come from its border, which is what makes `setBorder(new EmptyBorder(...))` the
 * usual way to pad a Swing UI.
 */
interface Border {
    fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int)
    fun getBorderInsets(c: Component): Insets
}

/** `javax.swing.border.EmptyBorder`: pure padding, drawing nothing. */
open class EmptyBorder(
    private val top: Int,
    private val left: Int,
    private val bottom: Int,
    private val right: Int,
) : Border {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {}
    override fun getBorderInsets(c: Component): Insets = Insets(top, left, bottom, right)
}

/** `javax.swing.border.LineBorder`: a rectangle [thickness] pixels wide in [lineColor]. */
open class LineBorder @JvmOverloads constructor(
    private val lineColor: Color,
    private val thickness: Int = 1,
) : Border {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.setColor(lineColor)
        for (i in 0 until thickness) {
            g.drawRect(x + i, y + i, width - 1 - i * 2, height - 1 - i * 2)
        }
    }

    override fun getBorderInsets(c: Component): Insets = Insets(thickness, thickness, thickness, thickness)
}

/** `javax.swing.border.BevelBorder`, drawn flat: the raised/lowered look needs no extra geometry here. */
open class BevelBorder @JvmOverloads constructor(
    private val bevelType: Int = RAISED,
) : Border {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.setColor(if (bevelType == RAISED) Color.WHITE else Color.GRAY)
        g.drawRect(x, y, width - 1, height - 1)
    }

    override fun getBorderInsets(c: Component): Insets = Insets(2, 2, 2, 2)

    companion object {
        @JvmField val RAISED = 0
        @JvmField val LOWERED = 1
    }
}

/** `javax.swing.border.EtchedBorder`. */
open class EtchedBorder : Border {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.setColor(Color.GRAY)
        g.drawRect(x, y, width - 1, height - 1)
    }

    override fun getBorderInsets(c: Component): Insets = Insets(2, 2, 2, 2)
}

/**
 * `javax.swing.border.TitledBorder`: a line border with the title written into the top edge, which is how a
 * Swing form groups its fields.
 */
open class TitledBorder @JvmOverloads constructor(
    private val title: String?,
    private val border: Border = LineBorder(Color.GRAY),
) : Border {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val label = title
        val metrics = g.getFontMetrics(TITLE_FONT)
        // The line is dropped to the title's middle and broken where the text goes, so the text sits IN it.
        val lineY = y + metrics.getHeight() / 2
        border.paintBorder(c, g, x, lineY, width, height - (lineY - y))
        if (label.isNullOrEmpty()) return
        val textWidth = metrics.stringWidth(label)
        c.getBackground()?.let {
            g.setColor(it)
            g.fillRect(x + GAP, lineY - metrics.getHeight() / 2, textWidth + GAP * 2, metrics.getHeight())
        }
        g.setFont(TITLE_FONT)
        g.setColor(c.getForeground())
        g.drawString(label, x + GAP * 2, y + metrics.getAscent())
    }

    override fun getBorderInsets(c: Component): Insets {
        val inner = border.getBorderInsets(c)
        return Insets(inner.top + TITLE_HEIGHT, inner.left + GAP, inner.bottom + GAP, inner.right + GAP)
    }

    private companion object {
        val TITLE_FONT = Font("SansSerif", Font.PLAIN, 12)
        const val GAP = 4
        const val TITLE_HEIGHT = 14
    }
}

/** `javax.swing.border.CompoundBorder`: [outside] around [inside]. */
open class CompoundBorder(private val outside: Border, private val inside: Border) : Border {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        outside.paintBorder(c, g, x, y, width, height)
        val o = outside.getBorderInsets(c)
        inside.paintBorder(c, g, x + o.left, y + o.top, width - o.left - o.right, height - o.top - o.bottom)
    }

    override fun getBorderInsets(c: Component): Insets {
        val o = outside.getBorderInsets(c)
        val i = inside.getBorderInsets(c)
        return Insets(o.top + i.top, o.left + i.left, o.bottom + i.bottom, o.right + i.right)
    }
}
