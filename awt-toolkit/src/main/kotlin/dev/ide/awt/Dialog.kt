package dev.ide.awt

/**
 * `java.awt.Dialog`: a window that opens over another one, and optionally blocks it.
 *
 * A modal dialog is the one place a UI toolkit has to run an event loop inside a call that has not returned:
 * `showMessageDialog` does not come back until the user dismisses it, yet the dialog has to paint and take
 * input while it waits. AWT does this with a nested pump on the event thread, and so does this: see
 * [ToolkitWindows.installedPump].
 */
open class Dialog @JvmOverloads constructor(
    private val owner: Window? = null,
    private var title: String? = null,
    private val modal: Boolean = false,
) : Window() {

    open fun getTitle(): String? = title

    open fun setTitle(value: String?) {
        title = value
    }

    open fun isModal(): Boolean = modal

    open fun getOwner(): Window? = owner

    /** Centre this dialog over its owner, or over [within] when it has none. */
    open fun centerOver(within: Window?) {
        val host = owner ?: within ?: return
        setLocation(
            host.getX() + (host.getWidth() - getWidth()) / 2,
            host.getY() + (host.getHeight() - getHeight()) / 2,
        )
    }

    /**
     * Show the dialog and, when it is modal, do not return until it closes.
     *
     * The loop is the nested pump: each turn the host delivers whatever input arrived, runs whatever was
     * posted, and paints. Without a pump installed there is no way to make progress, so it returns at once
     * rather than hanging the program forever, and the caller gets its default answer.
     */
    override fun setVisible(value: Boolean) {
        super.setVisible(value)
        if (!value || !modal) return
        val pump = ToolkitWindows.installedPump ?: return
        while (isDisplayable()) pump.pumpOnce()
    }
}

/**
 * How a modal dialog keeps the UI alive while the call that opened it is still on the stack.
 *
 * Installed by whatever drives the toolkit, because only it knows what one turn of its loop is: deliver input,
 * drain posted work, repaint. The toolkit itself owns no thread.
 */
fun interface ModalPump {
    fun pumpOnce()
}
