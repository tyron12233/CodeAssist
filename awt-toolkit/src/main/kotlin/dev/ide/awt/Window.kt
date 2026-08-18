package dev.ide.awt

import dev.ide.awt.event.KeyEvent
import dev.ide.awt.event.MouseEvent
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

    /** The component holding the pointer since it went down, so a drag and its release stay with it. */
    private var pressed: Component? = null

    /** The component keyboard events go to. */
    private var focusOwner: Component? = null

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
     * Route a pointer event at ([x], [y]) in window coordinates into the tree.
     *
     * The component that was PRESSED keeps receiving the drag and the release, even once the pointer has left
     * it, which is what AWT does and what makes "press a button, slide off, let go" cancel instead of firing.
     * A release back inside the pressed component is followed by a click, the sequence a button needs.
     */
    fun pointer(action: Int, x: Int, y: Int) {
        validate()
        when (action) {
            POINTER_DOWN -> {
                val target = componentAt(x, y)
                pressed = target
                focusOn(target)
                send(target, MouseEvent.MOUSE_PRESSED, x, y)
            }
            POINTER_MOVE -> pressed?.let { send(it, MouseEvent.MOUSE_DRAGGED, x, y) }
            POINTER_UP -> {
                val target = pressed ?: componentAt(x, y)
                pressed = null
                send(target, MouseEvent.MOUSE_RELEASED, x, y)
                if (hits(target, x, y)) send(target, MouseEvent.MOUSE_CLICKED, x, y)
            }
            POINTER_CANCEL -> {
                val target = pressed ?: return
                pressed = null
                // Outside its own bounds on purpose: a cancelled gesture must not read as a completed click.
                target.dispatchMouseEvent(MouseEvent(target, MouseEvent.MOUSE_EXITED, -1, -1))
            }
        }
    }

    /** A completed tap: the whole down/up sequence at one point, for a caller that has no gesture stream. */
    fun click(x: Int, y: Int) {
        pointer(POINTER_DOWN, x, y)
        pointer(POINTER_UP, x, y)
    }

    /**
     * Route a key event to the focused component, or to the window's content when nothing took focus.
     *
     * [action] is [KEY_DOWN] or [KEY_UP]; a printable [keyChar] on a press also produces the KEY_TYPED that
     * `keyTyped` listeners expect.
     */
    fun key(action: Int, keyCode: Int, keyChar: Char) {
        val target = focusOwner ?: this
        when (action) {
            KEY_DOWN -> {
                target.dispatchKeyEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, keyCode, keyChar))
                if (keyChar != CHAR_UNDEFINED) {
                    target.dispatchKeyEvent(KeyEvent(target, KeyEvent.KEY_TYPED, keyCode, keyChar))
                }
            }
            KEY_UP -> target.dispatchKeyEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, keyCode, keyChar))
        }
    }

    /**
     * Give [component] the keyboard, if it will take it. A component that is not focusable leaves focus where
     * it was, so pressing a button does not steal the keys from the panel that wanted them.
     */
    override fun focusOn(component: Component): Boolean {
        if (!component.isFocusable()) return false
        focusOwner = component
        return true
    }

    /** The component keys currently go to, or null when nothing has taken focus. */
    fun getFocusOwner(): Component? = focusOwner

    private fun send(target: Component, id: Int, x: Int, y: Int) {
        target.dispatchMouseEvent(MouseEvent(target, id, x - absoluteX(target), y - absoluteY(target)))
    }

    private fun hits(target: Component, x: Int, y: Int): Boolean =
        target.contains(x - absoluteX(target), y - absoluteY(target))

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

/** Pointer and key actions a host forwards, matching the platform-neutral constants in `:build-engine`. */
private const val POINTER_DOWN = 0
private const val POINTER_MOVE = 1
private const val POINTER_UP = 2
private const val POINTER_CANCEL = 3
private const val KEY_DOWN = 0
private const val KEY_UP = 1

/** `java.awt.event.KeyEvent.CHAR_UNDEFINED`: a key with no printable character. */
private const val CHAR_UNDEFINED = '\uFFFF'

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
