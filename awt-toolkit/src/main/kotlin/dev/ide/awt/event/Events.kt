package dev.ide.awt.event

import dev.ide.awt.AWTEvent
import dev.ide.awt.Component

/**
 * `java.awt.event.ActionEvent`: a button was activated. [actionCommand] defaults to the button's text, as in
 * Swing, because a shared listener switches on it.
 */
class ActionEvent(
    source: Any?,
    id: Int,
    private val actionCommand: String?,
) : AWTEvent(source, id) {
    fun getActionCommand(): String? = actionCommand

    companion object {
        @JvmField val ACTION_PERFORMED = 1001
    }
}

/** `java.awt.event.ActionListener`. */
fun interface ActionListener {
    fun actionPerformed(e: ActionEvent)
}

/** `java.awt.event.MouseEvent`. [x] and [y] are in the receiving component's own coordinate space. */
open class MouseEvent(
    source: Any?,
    id: Int,
    @JvmField val x: Int,
    @JvmField val y: Int,
    private val clickCount: Int = 1,
    private val button: Int = BUTTON1,
) : AWTEvent(source, id) {
    fun getX(): Int = x
    fun getY(): Int = y
    fun getClickCount(): Int = clickCount
    fun getButton(): Int = button
    fun getComponent(): Component? = source as? Component

    companion object {
        @JvmField val MOUSE_CLICKED = 500
        @JvmField val MOUSE_PRESSED = 501
        @JvmField val MOUSE_RELEASED = 502
        @JvmField val MOUSE_ENTERED = 504
        @JvmField val MOUSE_EXITED = 505
        @JvmField val MOUSE_MOVED = 503
        @JvmField val MOUSE_DRAGGED = 506
        @JvmField val BUTTON1 = 1
    }
}

/** `java.awt.event.MouseListener`. */
interface MouseListener {
    fun mouseClicked(e: MouseEvent)
    fun mousePressed(e: MouseEvent)
    fun mouseReleased(e: MouseEvent)
    fun mouseEntered(e: MouseEvent)
    fun mouseExited(e: MouseEvent)
}

/** `java.awt.event.MouseAdapter`: implement only the callbacks you care about. */
open class MouseAdapter : MouseListener {
    override fun mouseClicked(e: MouseEvent) {}
    override fun mousePressed(e: MouseEvent) {}
    override fun mouseReleased(e: MouseEvent) {}
    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
}

/** `java.awt.event.KeyEvent`. */
class KeyEvent(
    source: Any?,
    id: Int,
    private val keyCode: Int,
    private val keyChar: Char,
) : AWTEvent(source, id) {
    fun getKeyCode(): Int = keyCode
    fun getKeyChar(): Char = keyChar

    companion object {
        @JvmField val KEY_TYPED = 400
        @JvmField val KEY_PRESSED = 401
        @JvmField val KEY_RELEASED = 402
        @JvmField val VK_ENTER = 10
        @JvmField val VK_BACK_SPACE = 8
        @JvmField val VK_TAB = 9
        @JvmField val VK_ESCAPE = 27
        @JvmField val VK_SPACE = 32
        @JvmField val VK_LEFT = 37
        @JvmField val VK_UP = 38
        @JvmField val VK_RIGHT = 39
        @JvmField val VK_DOWN = 40
        @JvmField val VK_DELETE = 127
        @JvmField val VK_HOME = 36
        @JvmField val VK_END = 35
        @JvmField val VK_SHIFT = 16
        @JvmField val VK_CONTROL = 17
        @JvmField val VK_ALT = 18
        @JvmField val VK_A = 65
        @JvmField val VK_S = 83
        @JvmField val VK_W = 87
        @JvmField val VK_D = 68
    }
}

/** `java.awt.event.KeyListener`. */
interface KeyListener {
    fun keyTyped(e: KeyEvent)
    fun keyPressed(e: KeyEvent)
    fun keyReleased(e: KeyEvent)
}

/** `java.awt.event.KeyAdapter`. */
open class KeyAdapter : KeyListener {
    override fun keyTyped(e: KeyEvent) {}
    override fun keyPressed(e: KeyEvent) {}
    override fun keyReleased(e: KeyEvent) {}
}

/** `java.awt.event.MouseMotionListener`: movement, with or without a button held. */
interface MouseMotionListener {
    fun mouseMoved(e: MouseEvent)
    fun mouseDragged(e: MouseEvent)
}

/** `java.awt.event.MouseMotionAdapter`. */
open class MouseMotionAdapter : MouseMotionListener {
    override fun mouseMoved(e: MouseEvent) {}
    override fun mouseDragged(e: MouseEvent) {}
}

/**
 * `java.awt.event.MouseWheelEvent`. [wheelRotation] is in notches, positive when the content should scroll
 * down, matching AWT; [scrollAmount] is how many units one notch means.
 */
class MouseWheelEvent(
    source: Any?,
    id: Int,
    x: Int,
    y: Int,
    private val wheelRotation: Int,
    private val scrollAmount: Int = 3,
) : MouseEvent(source, id, x, y) {
    fun getWheelRotation(): Int = wheelRotation
    fun getScrollAmount(): Int = scrollAmount
    fun getUnitsToScroll(): Int = wheelRotation * scrollAmount

    companion object {
        @JvmField val MOUSE_WHEEL = 507
        @JvmField val WHEEL_UNIT_SCROLL = 0
    }
}

/** `java.awt.event.MouseWheelListener`. */
fun interface MouseWheelListener {
    fun mouseWheelMoved(e: MouseWheelEvent)
}

/** `java.awt.event.ItemEvent`: a checkbox, radio button, or combo box selection changed. */
class ItemEvent(
    source: Any?,
    id: Int,
    private val item: Any?,
    private val stateChange: Int,
) : AWTEvent(source, id) {
    fun getItem(): Any? = item
    fun getStateChange(): Int = stateChange

    companion object {
        @JvmField val ITEM_STATE_CHANGED = 701
        @JvmField val SELECTED = 1
        @JvmField val DESELECTED = 2
    }
}

/** `java.awt.event.ItemListener`. */
fun interface ItemListener {
    fun itemStateChanged(e: ItemEvent)
}
