package dev.ide.awt

import dev.ide.preview.RCanvas
import dev.ide.preview.RGraphics
import dev.ide.preview.RPaint
import dev.ide.preview.RPath

/**
 * `java.awt.Window`: a top-level container, and the unit the IDE's run engine counts to decide whether a GUI
 * program is still alive (see `ProgramWindows` in :jvm-build). A window is *displayable* between `setVisible(true)`
 * and [dispose], exactly the interval AWT uses for the same purpose.
 *
 * A window is also the [Surface] every component beneath it reaches for text metrics and repaint requests,
 * because it is the only node in the tree that the host has attached a backend to.
 */
open class Window : Container(), Surface {

    private var displayable = false
    private var needsFrame = true

    /** The backend the host attached, and the measuring graphics built over it, made once and reused. */
    private var backend: RGraphics? = null
    private var measuring: CanvasGraphics? = null

    init {
        // A program can size itself inside `main` (`pack()` measures text), before the host has any window to
        // attach a backend to, so a new window adopts whatever the host installed for the run.
        ToolkitWindows.installedBackend?.let { attachBackend(it) }
    }

    override fun surface(): Surface = this

    override fun invalidateFrame() {
        needsFrame = true
    }

    override fun measuringGraphics(): CanvasGraphics? = measuring

    /** Attach a drawing backend, so the tree beneath can measure text. Called by the host, once. */
    fun attachBackend(graphics: RGraphics) {
        backend = graphics
        measuring = CanvasGraphics(NoCanvas, graphics)
        invalidate()
    }

    fun backend(): RGraphics? = backend

    /** Whether a frame is owed since the last [paintTo]. The host reads this to skip idle repaints. */
    fun needsRepaint(): Boolean = needsFrame

    open fun isDisplayable(): Boolean = displayable

    override fun setVisible(value: Boolean) {
        super.setVisible(value)
        if (value) {
            displayable = true
            ToolkitWindows.opened(this)
            invalidateFrame()
        }
    }

    /** Release the window. It stops being displayable, which is what ends a GUI run. */
    open fun dispose() {
        displayable = false
        super.setVisible(false)
        ToolkitWindows.closed(this)
    }

    /** Size the tree to the window's current bounds, then draw it into [canvas]. */
    fun paintTo(canvas: RCanvas) {
        val graphics = backend ?: return
        validate()
        val g = CanvasGraphics(canvas, graphics, getBackground() ?: Color.WHITE)
        g.push(0, 0, getWidth(), getHeight())
        try {
            paint(g)
        } finally {
            g.pop()
        }
        needsFrame = false
    }

    /**
     * Route a click at ([x], [y]) in window coordinates to the deepest component under it, as a press followed
     * by a release and then a click, which is the sequence a button needs to fire.
     */
    fun click(x: Int, y: Int) {
        validate()
        val target = componentAt(x, y)
        val localX = x - absoluteX(target)
        val localY = y - absoluteY(target)
        for (id in intArrayOf(
            dev.ide.awt.event.MouseEvent.MOUSE_PRESSED,
            dev.ide.awt.event.MouseEvent.MOUSE_RELEASED,
            dev.ide.awt.event.MouseEvent.MOUSE_CLICKED,
        )) {
            target.dispatchMouseEvent(dev.ide.awt.event.MouseEvent(target, id, localX, localY))
        }
    }

    private fun absoluteX(c: Component): Int {
        var x = 0
        var node: Component? = c
        while (node != null && node !== this) {
            x += node.getX()
            node = node.parent
        }
        return x
    }

    private fun absoluteY(c: Component): Int {
        var y = 0
        var node: Component? = c
        while (node != null && node !== this) {
            y += node.getY()
            node = node.parent
        }
        return y
    }
}

/** `java.awt.Frame`: a window with a title. */
open class Frame @JvmOverloads constructor(private var title: String? = null) : Window() {
    open fun getTitle(): String? = title

    open fun setTitle(value: String?) {
        title = value
    }
}

/**
 * Every window the program currently has open. The run engine polls this to decide when a GUI program has
 * finished, the same role `java.awt.Window.getWindows()` plays for real Swing.
 */
object ToolkitWindows {
    private val open = ArrayList<Window>()

    /**
     * The drawing backend a newly created [Window] adopts. The host installs it before starting the program,
     * because a window that measures itself during `main` needs a real typeface before the host can reach it.
     * Null (the default) leaves a window unattached until [Window.attachBackend] is called, which is what the
     * headless tests do.
     */
    @Volatile
    @JvmStatic
    var installedBackend: RGraphics? = null

    @Synchronized
    internal fun opened(w: Window) {
        if (open.none { it === w }) open.add(w)
    }

    @Synchronized
    internal fun closed(w: Window) {
        open.removeAll { it === w }
    }

    /** The windows still displayable, in the order they were opened. */
    @Synchronized
    fun displayable(): List<Window> = open.filter { it.isDisplayable() }

    /** Close everything, which is what Stop and the end of a run do. */
    @Synchronized
    fun disposeAll() {
        open.toList().forEach { it.dispose() }
    }
}

/**
 * A canvas that draws nothing, used for the measure-only graphics a window hands its children: text
 * measurement goes through [RGraphics], which needs no canvas, and nothing else on that instance is called.
 */
internal object NoCanvas : RCanvas {
    override fun save(): Int = 0
    override fun restore() {}
    override fun translate(dx: Float, dy: Float) {}
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {}
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: RPaint) {}
    override fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: RPaint) {}
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: RPaint) {}
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: RPaint) {}
    override fun drawPath(path: RPath, paint: RPaint) {}
    override fun drawImage(img: dev.ide.preview.RImage, l: Float, t: Float, r: Float, b: Float, tintArgb: Int?) {}
    override fun drawText(text: CharSequence, x: Float, y: Float, paint: RPaint) {}
}
