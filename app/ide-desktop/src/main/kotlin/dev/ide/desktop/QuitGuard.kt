package dev.ide.desktop

import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.WindowEvent

/**
 * Keeps the OS-level Quit (Cmd+Q, the app menu's Quit, the Dock's) from taking the IDE down when the window in
 * front belongs to a program the user ran.
 *
 * A desktop Run executes the program inside the IDE's own process (the bytecode interpreter, with Swing and
 * Compose bridged to the IDE's copies), so its windows are this process's windows and macOS quits the process for
 * them. To the user that window is a separate app, and quitting it should end it, not the IDE. So when the active
 * window is not the IDE's, the quit is cancelled and that window is asked to close as if its close button were
 * pressed: a Compose window runs its `onCloseRequest`, a Swing frame its close operation, and the run ends the way
 * it does when the user closes the window. With the IDE in front, Quit quits as before.
 */
internal object QuitGuard {

    /** The IDE's main window; a window owned by it (a dialog the IDE opens) counts as the IDE's too. */
    @Volatile var ideWindow: Window? = null

    /** Install the handler, where the platform has one (macOS). Elsewhere there is no process-wide Quit. */
    fun install() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
        desktop.setQuitHandler { _, response ->
            val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            val program = programWindowToClose(active, ideWindow) { it.owner }
            if (program == null) {
                response.performQuit()
            } else {
                response.cancelQuit()
                program.dispatchEvent(WindowEvent(program, WindowEvent.WINDOW_CLOSING))
            }
        }
    }
}

/**
 * The window a Quit should close instead of quitting, or null to quit: [active] when it is a window and neither
 * [ide] nor owned (through [owner], transitively) by it. Null while the IDE window is unknown, so a Quit before the
 * IDE has shown its window still quits.
 */
internal fun <W : Any> programWindowToClose(active: W?, ide: W?, owner: (W) -> W?): W? {
    if (active == null || ide == null) return null
    var w: W? = active
    while (w != null) {
        if (w == ide) return null
        w = owner(w)
    }
    return active
}
