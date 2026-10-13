package dev.ide.ui.ext

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlin.time.TimeSource

/**
 * The app-wide commands: switching and closing tabs, the go-to searches, the tool windows, run and build.
 *
 * The editor's commands act on a buffer and live in [EditorCommands]-style ids of their own; these act on the
 * IDE around it, so they are bound in the keymap's Global context and fire wherever focus is. Their bodies
 * are UI state (the tab strip, the palette, the panels), so they run in the screen that owns that state,
 * through the runner it installs on [AppCommandHost].
 */
object AppCommands {
    const val SWITCHER = "app.switcher"
    const val SWITCHER_BACK = "app.switcherBack"
    const val RECENT_FILES = "app.recentFiles"
    const val NEXT_TAB = "app.nextTab"
    const val PREVIOUS_TAB = "app.previousTab"
    const val CLOSE_TAB = "app.closeTab"
    const val CLOSE_OTHER_TABS = "app.closeOtherTabs"
    const val CLOSE_ALL_TABS = "app.closeAllTabs"
    const val REOPEN_CLOSED_TAB = "app.reopenClosedTab"

    /** Tab [n] (1..8) of the strip, or the last tab for 9, as in a browser. */
    fun goToTab(n: Int) = "app.goToTab$n"
    const val GO_TO_TAB_PREFIX = "app.goToTab"

    const val GO_TO_FILE = "app.goToFile"
    const val GO_TO_CLASS = "app.goToClass"
    const val GO_TO_SYMBOL = "app.goToSymbol"
    const val FIND_ACTION = "app.findAction"
    const val SEARCH_EVERYWHERE = "app.searchEverywhere"
    const val FIND_IN_FILES = "app.findInFiles"

    const val TOGGLE_PROJECT = "app.toggleProjectTree"
    const val TOGGLE_CONSOLE = "app.toggleConsole"
    const val HIDE_ALL_TOOL_WINDOWS = "app.hideAllToolWindows"
    const val FOCUS_EDITOR = "app.focusEditor"

    const val RUN = "app.run"
    const val RUN_TESTS = "app.runTests"
    const val BUILD = "app.build"
    const val STOP = "app.stop"
    const val SAVE_ALL = "app.saveAll"
    const val SETTINGS = "app.settings"
    const val NEW_FILE = "app.newFile"
}

/**
 * One app-wide command's defaults: its [title] and [group] for the Keymap settings page, and its shortcut
 * [specs], the first being the one menus show and the user rebinds. The rest are aliases (IntelliJ's own key
 * next to the one everyone knows from a browser). An empty list is a command with no default shortcut, still
 * listed so the user can give it one.
 */
class AppKeyDefault(
    val actionId: String,
    val title: String,
    val group: String,
    val specs: List<String>,
)

/**
 * The app-wide default keymap: IntelliJ's bindings, plus the universal aliases (Ctrl+Tab, Ctrl+W,
 * Ctrl+Shift+T). Kept clear of [EDITOR_KEY_DEFAULTS] (a test checks), and of keys the editor needs for
 * itself, since the editor resolves its shortcuts before its own caret and popup keys: no Escape (it closes
 * the editor's popups) and no Alt+Left/Right (word-by-word caret movement on a Mac).
 *
 * `ctrl+Tab` is the physical Control key on purpose. Command+Tab belongs to macOS.
 */
val APP_KEY_DEFAULTS: List<AppKeyDefault> = buildList {
    fun add(id: String, title: String, group: String, vararg specs: String) =
        add(AppKeyDefault(id, title, group, specs.toList()))

    val tabs = "Tabs"
    add(AppCommands.SWITCHER, "Switch to recent file", tabs, "ctrl+Tab")
    add(AppCommands.SWITCHER_BACK, "Switch to recent file (backwards)", tabs, "ctrl+shift+Tab")
    add(AppCommands.RECENT_FILES, "Recent files", tabs, "primary+E")
    add(AppCommands.NEXT_TAB, "Next tab", tabs, "ctrl+PageDown", "primary+shift+RightBracket")
    add(AppCommands.PREVIOUS_TAB, "Previous tab", tabs, "ctrl+PageUp", "primary+shift+LeftBracket")
    add(AppCommands.CLOSE_TAB, "Close tab", tabs, "primary+W", "ctrl+F4")
    add(AppCommands.CLOSE_OTHER_TABS, "Close other tabs", tabs)
    add(AppCommands.CLOSE_ALL_TABS, "Close all tabs", tabs)
    add(AppCommands.REOPEN_CLOSED_TAB, "Reopen closed tab", tabs, "primary+shift+T")
    for (n in 1..9) add(AppCommands.goToTab(n), if (n == 9) "Go to last tab" else "Go to tab $n", tabs, "primary+$n")

    val nav = "Navigation"
    add(AppCommands.SEARCH_EVERYWHERE, "Search everywhere (or press Shift twice)", nav)
    add(AppCommands.GO_TO_FILE, "Go to file", nav, "primary+shift+N")
    add(AppCommands.GO_TO_CLASS, "Go to class", nav, "primary+N")
    add(AppCommands.GO_TO_SYMBOL, "Go to symbol", nav, "primary+alt+shift+N")
    add(AppCommands.FIND_ACTION, "Find action", nav, "primary+shift+A")
    add(AppCommands.FIND_IN_FILES, "Find in files", nav, "primary+shift+F")

    val panels = "Tool windows"
    add(AppCommands.TOGGLE_PROJECT, "Project", panels, "alt+1")
    add(AppCommands.TOGGLE_CONSOLE, "Console", panels, "alt+4")
    add(AppCommands.HIDE_ALL_TOOL_WINDOWS, "Hide all tool windows", panels, "primary+shift+F12")
    add(AppCommands.FOCUS_EDITOR, "Back to the editor (or press Escape)", panels)

    val run = "Run & project"
    add(AppCommands.RUN, "Run", run, "shift+F10")
    add(AppCommands.RUN_TESTS, "Run tests in this file", run, "ctrl+shift+F10")
    add(AppCommands.BUILD, "Build", run, "primary+F9")
    add(AppCommands.STOP, "Stop", run, "primary+F2")
    add(AppCommands.SAVE_ALL, "Save all", run, "primary+shift+S")
    add(AppCommands.SETTINGS, "Settings", run, "primary+alt+S", "primary+Comma")
    add(AppCommands.NEW_FILE, "New file", run, "alt+Insert", "primary+alt+N")
}

/**
 * Existing actions that do what an app command does, by their own ids: the palette's "Run Build" and "Stop
 * Build" (engine actions) and "Settings" (a UI action). The palette lists those rather than a second copy of
 * the app command, and shows the app command's shortcut beside them.
 */
val APP_COMMAND_EQUIVALENTS: Map<String, String> = mapOf(
    "ide.runBuild" to AppCommands.RUN,
    "ide.stopBuild" to AppCommands.STOP,
    "ui.hub" to AppCommands.SETTINGS,
)

/** The app default for [actionId], or null for a command that is not an app-wide one. */
fun appKeyDefault(actionId: String): AppKeyDefault? = APP_KEY_DEFAULTS.firstOrNull { it.actionId == actionId }

/**
 * The app defaults as a lookup, for a host with no keymap installed (a test, a preview, a host that wires no
 * engine), so it keeps the app's shortcuts the way [EditorKeyFallback] keeps the editor's. No user overrides
 * and no chords there; the real keymap has those.
 */
internal object AppKeyFallback {
    private class Stroke(
        val key: String, val primary: Boolean, val ctrl: Boolean, val meta: Boolean,
        val shift: Boolean, val alt: Boolean,
    ) {
        fun matches(key: String, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): Boolean {
            if (key != this.key || shift != this.shift || alt != this.alt) return false
            return if (primary) ctrl || meta else ctrl == this.ctrl && meta == this.meta
        }
    }

    private fun parse(spec: String): Stroke? {
        if (' ' in spec) return null // a chord: the fallback has no chord state
        val parts = spec.split('+')
        val mods = parts.dropLast(1).map { it.lowercase() }.toSet()
        return Stroke(
            key = parts.last(), primary = "primary" in mods, ctrl = "ctrl" in mods, meta = "meta" in mods,
            shift = "shift" in mods, alt = "alt" in mods,
        )
    }

    private val strokes: List<Pair<Stroke, String>> =
        APP_KEY_DEFAULTS.flatMap { d -> d.specs.mapNotNull { s -> parse(s)?.let { it to d.actionId } } }

    fun resolve(key: String, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): String? =
        strokes.firstOrNull { it.first.matches(key, ctrl, shift, alt, meta) }?.second
}

/**
 * Where an app-wide command runs. The screen that owns the tabs and panels (the project's editor screen)
 * installs a [Runner] while it is showing; with none installed, an app command is simply not handled.
 */
object AppCommandHost {
    /** Runs an app command; false when it does not know [actionId] or has nothing to act on. */
    fun interface Runner {
        fun run(actionId: String): Boolean
    }

    private val runners = ArrayList<Runner>()

    /** Install [runner]; the most recent installation wins until it is disposed. */
    fun install(runner: Runner): Registration {
        runners.add(runner)
        return Registration { runners.remove(runner) }
    }

    /** Run [actionId] through the installed runner. A runner that throws costs the command, not the keyboard. */
    fun run(actionId: String): Boolean {
        val runner = runners.lastOrNull() ?: return false
        return runCatching { runner.run(actionId) }.getOrDefault(false)
    }

    /**
     * A modal key handler that sees every key event before anything else in the window, while set: the file
     * switcher, which owns Tab, the arrows and the release of Ctrl for as long as it is open. Returns true to
     * consume. Set and cleared by whoever opens the modal; main thread only.
     */
    var interceptor: ((KeyEvent) -> Boolean)? = null
}

/**
 * The app-level key handling, attached at the root of the window.
 *
 * [onPreviewKey] runs first (root to leaf) and only for the modal [AppCommandHost.interceptor] and the
 * double-Shift gesture, neither of which resolves a binding. [onKey] runs last, for a key nothing inside
 * consumed, and resolves it in the Global context. A focused editor resolves its own keys (Global bindings
 * included) and hands an app command to [AppCommandHost] itself, so a key press is resolved once, which a
 * keymap that holds chord state requires.
 */
object AppKeys {
    fun onPreviewKey(ev: KeyEvent): Boolean {
        AppCommandHost.interceptor?.let { if (it(ev)) return true }
        trackDoubleShift(ev)
        return false
    }

    fun onKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        val name = keymapKeyName(ev.key)
        if (name != null) {
            when (val outcome = KeymapHost.resolve(
                name, ev.isCtrlPressed, ev.isShiftPressed, ev.isAltPressed, ev.isMetaPressed, inEditor = false,
            )) {
                is KeymapHost.Outcome.Command -> if (AppCommandHost.run(outcome.actionId)) return true
                is KeymapHost.Outcome.Pending -> return true
                KeymapHost.Outcome.None -> Unit
            }
        }
        // IntelliJ's Escape: back to the editor from a tool window. Only an Escape nothing else wanted gets this
        // far (a dialog, the palette and the editor's own popups all take theirs first), which is why it is a
        // fallback here and not a binding: as a binding the editor would resolve it before closing its popups.
        // Never consumed, so the platform still sees it (Android may treat Escape as Back).
        if (ev.key == Key.Escape && !ev.isCtrlPressed && !ev.isShiftPressed && !ev.isAltPressed && !ev.isMetaPressed) {
            AppCommandHost.run(AppCommands.FOCUS_EDITOR)
        }
        return false
    }

    // ---- double Shift: Search Everywhere, as in IntelliJ ----

    private var shiftDownAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var shiftClean = false
    private var lastShiftTapAt: TimeSource.Monotonic.ValueTimeMark? = null

    private fun isShift(key: Key) = key == Key.ShiftLeft || key == Key.ShiftRight

    /**
     * Two taps of Shift alone, close together, open Search Everywhere. A tap is a press and release with no
     * other key between and not held long, so typing a capital, Shift+arrow selection and Shift-clicks never
     * count. Never consumes: the editor and the text fields still see every Shift.
     */
    private fun trackDoubleShift(ev: KeyEvent) {
        val now = TimeSource.Monotonic.markNow()
        if (!isShift(ev.key)) {
            if (ev.type == KeyEventType.KeyDown) { shiftClean = false; lastShiftTapAt = null }
            return
        }
        when (ev.type) {
            KeyEventType.KeyDown -> if (shiftDownAt == null) {
                shiftDownAt = now
                shiftClean = !ev.isCtrlPressed && !ev.isAltPressed && !ev.isMetaPressed
            }
            KeyEventType.KeyUp -> {
                val down = shiftDownAt
                shiftDownAt = null
                val tap = shiftClean && down != null && (now - down).inWholeMilliseconds < DOUBLE_SHIFT_MS
                if (!tap) { lastShiftTapAt = null; return }
                val previous = lastShiftTapAt
                if (previous != null && (now - previous).inWholeMilliseconds < DOUBLE_SHIFT_MS) {
                    lastShiftTapAt = null
                    AppCommandHost.run(AppCommands.SEARCH_EVERYWHERE)
                } else {
                    lastShiftTapAt = now
                }
            }
            else -> Unit
        }
    }

    private const val DOUBLE_SHIFT_MS = 400L
}
