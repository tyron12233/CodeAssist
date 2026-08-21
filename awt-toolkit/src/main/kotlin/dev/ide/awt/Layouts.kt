package dev.ide.awt

/** `java.awt.LayoutManager`: decides where a container's children go and how big the container wants to be. */
interface LayoutManager {
    fun layoutContainer(parent: Container)
    fun preferredLayoutSize(parent: Container): Dimension
}

/**
 * `java.awt.BorderLayout`: up to five children, one per region. NORTH and SOUTH take their preferred height
 * and the full width; WEST and EAST take their preferred width and whatever height is left; CENTER takes the
 * rest. The default layout of every `JFrame` content pane, so most programs get it without asking.
 */
class BorderLayout @JvmOverloads constructor(
    private val hgap: Int = 0,
    private val vgap: Int = 0,
) : LayoutManager {

    override fun layoutContainer(parent: Container) {
        val insets = parent.getInsets()
        var top = insets.top
        var bottom = parent.getHeight() - insets.bottom
        var left = insets.left
        var right = parent.getWidth() - insets.right

        parent.child(NORTH)?.let {
            val h = it.getPreferredSize().height
            it.setBounds(left, top, right - left, h)
            top += h + vgap
        }
        parent.child(SOUTH)?.let {
            val h = it.getPreferredSize().height
            it.setBounds(left, bottom - h, right - left, h)
            bottom -= h + vgap
        }
        parent.child(WEST)?.let {
            val w = it.getPreferredSize().width
            it.setBounds(left, top, w, bottom - top)
            left += w + hgap
        }
        parent.child(EAST)?.let {
            val w = it.getPreferredSize().width
            it.setBounds(right - w, top, w, bottom - top)
            right -= w + hgap
        }
        parent.child(CENTER)?.setBounds(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val north = parent.child(NORTH)?.getPreferredSize()
        val south = parent.child(SOUTH)?.getPreferredSize()
        val west = parent.child(WEST)?.getPreferredSize()
        val east = parent.child(EAST)?.getPreferredSize()
        val center = parent.child(CENTER)?.getPreferredSize()

        val middleWidth = (west?.width ?: 0) + (center?.width ?: 0) + (east?.width ?: 0) +
            hgap * listOfNotNull(west, east).size
        val middleHeight = maxOf(west?.height ?: 0, center?.height ?: 0, east?.height ?: 0)

        val insets = parent.getInsets()
        val width = maxOf(middleWidth, north?.width ?: 0, south?.width ?: 0)
        val height = middleHeight + (north?.height ?: 0) + (south?.height ?: 0) +
            vgap * listOfNotNull(north, south).size
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    /**
     * The child added with [region]. A child added with no constraint counts as CENTER, as in AWT, so
     * `frame.add(panel)` lands where the program expects.
     */
    private fun Container.child(region: String): Component? = components().lastOrNull { c ->
        val constraint = constraintFor(c)
        c.isVisible() && (constraint == region || (constraint == null && region == CENTER))
    }

    companion object {
        @JvmField val NORTH = "North"
        @JvmField val SOUTH = "South"
        @JvmField val EAST = "East"
        @JvmField val WEST = "West"
        @JvmField val CENTER = "Center"
        @JvmField val PAGE_START = NORTH
        @JvmField val PAGE_END = SOUTH
        @JvmField val LINE_START = WEST
        @JvmField val LINE_END = EAST
    }
}

/**
 * `java.awt.FlowLayout`: children at their preferred size, left to right, wrapping to a new row when the next
 * one will not fit. Rows are centred by default, as in AWT.
 */
class FlowLayout @JvmOverloads constructor(
    private val align: Int = CENTER,
    private val hgap: Int = 5,
    private val vgap: Int = 5,
) : LayoutManager {

    override fun layoutContainer(parent: Container) {
        val insets = parent.getInsets()
        val maxWidth = parent.getWidth() - insets.left - insets.right
        var y = insets.top + vgap
        var row = ArrayList<Component>()
        var rowWidth = 0
        var rowHeight = 0

        fun placeRow() {
            if (row.isEmpty()) return
            var x = insets.left + when (align) {
                LEFT -> hgap
                RIGHT -> maxOf(hgap, maxWidth - rowWidth - hgap)
                else -> maxOf(hgap, (maxWidth - rowWidth) / 2)
            }
            for (c in row) {
                val size = c.getPreferredSize()
                c.setBounds(x, y + (rowHeight - size.height) / 2, size.width, size.height)
                x += size.width + hgap
            }
            y += rowHeight + vgap
            row = ArrayList()
            rowWidth = 0
            rowHeight = 0
        }

        for (c in parent.components()) {
            if (!c.isVisible()) continue
            val size = c.getPreferredSize()
            val added = if (row.isEmpty()) size.width else rowWidth + hgap + size.width
            if (row.isNotEmpty() && added > maxWidth) placeRow()
            rowWidth = if (row.isEmpty()) size.width else rowWidth + hgap + size.width
            rowHeight = maxOf(rowHeight, size.height)
            row.add(c)
        }
        placeRow()
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        var width = 0
        var height = 0
        var count = 0
        for (c in parent.components()) {
            if (!c.isVisible()) continue
            val size = c.getPreferredSize()
            width += size.width
            height = maxOf(height, size.height)
            count++
        }
        val insets = parent.getInsets()
        return Dimension(
            width + hgap * (count + 1) + insets.left + insets.right,
            height + vgap * 2 + insets.top + insets.bottom,
        )
    }

    companion object {
        @JvmField val LEFT = 0
        @JvmField val CENTER = 1
        @JvmField val RIGHT = 2
    }
}

/**
 * `java.awt.GridLayout`: equal cells in [rows] by [cols]. A zero in either means "as many as it takes",
 * resolved against the child count the way AWT does.
 */
class GridLayout @JvmOverloads constructor(
    private val rows: Int = 1,
    private val cols: Int = 0,
    private val hgap: Int = 0,
    private val vgap: Int = 0,
) : LayoutManager {

    override fun layoutContainer(parent: Container) {
        val visible = parent.components().filter { it.isVisible() }
        if (visible.isEmpty()) return
        val (r, c) = grid(visible.size)
        val insets = parent.getInsets()
        val cellWidth = (parent.getWidth() - insets.left - insets.right - (c - 1) * hgap) / c
        val cellHeight = (parent.getHeight() - insets.top - insets.bottom - (r - 1) * vgap) / r

        visible.forEachIndexed { i, comp ->
            val col = i % c
            val row = i / c
            comp.setBounds(
                insets.left + col * (cellWidth + hgap),
                insets.top + row * (cellHeight + vgap),
                maxOf(0, cellWidth),
                maxOf(0, cellHeight),
            )
        }
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val visible = parent.components().filter { it.isVisible() }
        if (visible.isEmpty()) return Dimension(0, 0)
        val (r, c) = grid(visible.size)
        var cellWidth = 0
        var cellHeight = 0
        for (comp in visible) {
            val size = comp.getPreferredSize()
            cellWidth = maxOf(cellWidth, size.width)
            cellHeight = maxOf(cellHeight, size.height)
        }
        val insets = parent.getInsets()
        return Dimension(
            c * cellWidth + (c - 1) * hgap + insets.left + insets.right,
            r * cellHeight + (r - 1) * vgap + insets.top + insets.bottom,
        )
    }

    /** Resolve the declared rows/cols against [count]: the non-zero dimension wins and the other follows. */
    private fun grid(count: Int): Pair<Int, Int> = when {
        rows > 0 -> rows to ((count + rows - 1) / rows)
        cols > 0 -> ((count + cols - 1) / cols) to cols
        else -> 1 to count
    }
}
