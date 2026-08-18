package dev.ide.swing

import dev.ide.awt.BorderLayout
import dev.ide.awt.Color
import dev.ide.awt.Component
import dev.ide.awt.Container
import dev.ide.awt.Dimension
import dev.ide.awt.Frame
import dev.ide.awt.Graphics
import dev.ide.awt.LayoutManager

/** `javax.swing.WindowConstants`, with AWT's numbering, since programs pass the ints around. */
interface WindowConstants {
    companion object {
        @JvmField val DO_NOTHING_ON_CLOSE = 0
        @JvmField val HIDE_ON_CLOSE = 1
        @JvmField val DISPOSE_ON_CLOSE = 2
        @JvmField val EXIT_ON_CLOSE = 3
    }
}

/**
 * `javax.swing.JFrame`: the top-level window a program builds its UI in.
 *
 * As in Swing, the frame is not itself the container the program fills. `add` and `setLayout` forward to the
 * content pane, so both the modern `frame.add(panel)` and the older `frame.getContentPane().add(panel)` reach
 * the same place.
 *
 * `EXIT_ON_CLOSE` here does NOT call `System.exit`. In this toolkit closing the last window is already what
 * ends the run (see `dev.ide.awt.ToolkitWindows`), so the distinction that made `EXIT_ON_CLOSE` dangerous
 * under real Swing, where it reached `System.exit` inside `java.desktop` and took the IDE with it, does not
 * arise: the close operation only decides whether the window hides or is disposed.
 */
open class JFrame @JvmOverloads constructor(title: String? = null) : Frame(title), WindowConstants {

    private val contentPane: JPanel = JPanel(BorderLayout()).apply { setBackground(Color.WHITE) }
    private var closeOperation = WindowConstants.HIDE_ON_CLOSE

    init {
        setBackground(Color.WHITE)
        addChild(contentPane, null)
    }

    open fun getContentPane(): Container = contentPane

    open fun setContentPane(pane: Container?) {
        throw UnsupportedOperationException("replacing a JFrame's content pane is not supported yet")
    }

    override fun add(comp: Component): Component = contentPane.add(comp)

    override fun add(comp: Component, constraint: Any?): Component = contentPane.add(comp, constraint)

    override fun remove(comp: Component) = contentPane.remove(comp)

    override fun setLayout(manager: LayoutManager?) = contentPane.setLayout(manager)

    override fun getLayout(): LayoutManager? = contentPane.getLayout()

    open fun setDefaultCloseOperation(operation: Int) {
        closeOperation = operation
    }

    open fun getDefaultCloseOperation(): Int = closeOperation

    /** `setLocationByPlatform`/`setLocationRelativeTo`: the surface decides placement, so these are accepted
     *  and ignored rather than failing a program that calls them. */
    open fun setLocationByPlatform(value: Boolean) {}

    open fun setLocationRelativeTo(c: Component?) {}

    open fun setResizable(value: Boolean) {}

    /** Size the frame to what its content wants, as `pack` does. */
    open fun pack() {
        validate()
        val preferred = contentPane.getPreferredSize()
        setSize(preferred.width, preferred.height)
        invalidate()
        validate()
    }

    override fun doLayout() {
        // The content pane always fills the frame; the frame itself has no layout manager of its own.
        contentPane.setBounds(0, 0, getWidth(), getHeight())
    }

    override fun computePreferredSize(): Dimension = contentPane.getPreferredSize()

    override fun paint(g: Graphics) {
        getBackground()?.let {
            g.setColor(it)
            g.fillRect(0, 0, getWidth(), getHeight())
        }
        super.paint(g)
    }

    /** Act on the close button, honouring the frame's close operation. */
    open fun close() {
        when (closeOperation) {
            WindowConstants.DO_NOTHING_ON_CLOSE -> Unit
            WindowConstants.HIDE_ON_CLOSE -> setVisible(false)
            else -> dispose()
        }
    }

    companion object {
        @JvmField val DO_NOTHING_ON_CLOSE = WindowConstants.DO_NOTHING_ON_CLOSE
        @JvmField val HIDE_ON_CLOSE = WindowConstants.HIDE_ON_CLOSE
        @JvmField val DISPOSE_ON_CLOSE = WindowConstants.DISPOSE_ON_CLOSE
        @JvmField val EXIT_ON_CLOSE = WindowConstants.EXIT_ON_CLOSE
    }
}

/**
 * `javax.swing.SwingUtilities`. There is no separate event-dispatch thread here: the toolkit runs on whatever
 * thread the run engine drives it from, and the surface drains [ToolkitEventQueue] between frames. So
 * `invokeLater` queues and `invokeAndWait` runs inline, the same choice the IDE's headless
 * `javax.swing.SwingUtilities` shim on ART already makes.
 */
object SwingUtilities {
    @JvmStatic
    fun invokeLater(doRun: Runnable?) {
        if (doRun != null) ToolkitEventQueue.post(doRun)
    }

    @JvmStatic
    fun invokeAndWait(doRun: Runnable?) {
        doRun?.run()
    }

    @JvmStatic
    fun isEventDispatchThread(): Boolean = true

    /** The window a component belongs to, or null when it is not in a tree yet. */
    @JvmStatic
    fun getWindowAncestor(c: Component?): dev.ide.awt.Window? {
        var node: Component? = c
        while (node != null) {
            if (node is dev.ide.awt.Window) return node
            node = node.parent
        }
        return null
    }
}

/** Work posted with `invokeLater`, drained by the surface between frames. */
object ToolkitEventQueue {
    private val pending = ArrayDeque<Runnable>()

    @Synchronized
    fun post(work: Runnable) {
        pending.addLast(work)
    }

    @Synchronized
    private fun take(): List<Runnable> {
        val batch = pending.toList()
        pending.clear()
        return batch
    }

    /** Run everything queued so far. Work posted by that work runs on the next drain, so a task that reposts
     *  itself cannot starve the frame it was called from. */
    fun drain() {
        take().forEach { it.run() }
    }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    @Synchronized
    fun clear() {
        pending.clear()
    }
}
