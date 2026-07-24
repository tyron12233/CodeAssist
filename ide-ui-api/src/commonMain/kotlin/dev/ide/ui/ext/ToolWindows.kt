package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import dev.ide.ui.backend.IdeBackend

/**
 * The Compose-bearing half of the hybrid extension model: registries for contributions that render their own
 * UI, which can't cross the boundary as neutral data. A tool window (a dockable panel), a top-level screen,
 * and an editor view mode each carry a `@Composable` body, so they live in Compose-side registries (modeled
 * on `TreeIcons`: process-global, observable) rather than going through `IdeBackend`.
 *
 * Built-in surfaces stay native for now; these registries are the seam a plugin (Phase C) or a later built-in
 * migration contributes through. The host renders contributions alongside the built-ins.
 */

// ---------------------------------------------------------------------------
// Tool windows (dockable panels)
// ---------------------------------------------------------------------------

/** Where a tool window docks. LEFT = the side-rail / navigator region; BOTTOM = the build-console region. */
enum class ToolWindowAnchor { LEFT, RIGHT, BOTTOM }

/** What a tool-window body is handed when rendered. */
interface ToolWindowContext {
    val backend: IdeBackend
    val activeFilePath: String?
}

/**
 * A dockable panel. [content] is the body, rendered when the tool window is the selected one for its anchor.
 * [iconId] resolves through the action/icon registry; [order] sorts within an anchor.
 */
class ToolWindowContribution(
    val id: String,
    val title: String,
    val iconId: String,
    val anchor: ToolWindowAnchor,
    val order: Int = 1000,
    val content: @Composable (ToolWindowContext) -> Unit,
)

object ToolWindowRegistry {
    private val items = mutableStateListOf<ToolWindowContribution>()

    fun register(toolWindow: ToolWindowContribution): Registration {
        items.add(toolWindow)
        return Registration { items.remove(toolWindow) }
    }

    fun forAnchor(anchor: ToolWindowAnchor): List<ToolWindowContribution> =
        items.filter { it.anchor == anchor }.sortedWith(compareBy({ it.order }, { it.title }))
}

// ---------------------------------------------------------------------------
// Overlays (app-wide floating layers: dialogs, prompts)
// ---------------------------------------------------------------------------

/** What an overlay body is handed: the backend it observes (e.g. a plugin's permission-request flow). */
interface OverlayContext {
    val backend: IdeBackend
}

/**
 * An app-wide overlay layer rendered above every screen (the host composes all registered overlays in
 * `AppOverlays`). A plugin uses this for a floating surface it must show regardless of the current screen —
 * e.g. the AI agent's write-permission prompt. The body decides its own visibility (it typically observes a
 * backend flow and renders nothing until there's something to show).
 */
class OverlayContribution(
    val id: String,
    val content: @Composable (OverlayContext) -> Unit,
)

object OverlayRegistry {
    private val items = mutableStateListOf<OverlayContribution>()

    fun register(overlay: OverlayContribution): Registration {
        items.add(overlay)
        return Registration { items.remove(overlay) }
    }

    fun all(): List<OverlayContribution> = items.toList()
}

// ---------------------------------------------------------------------------
// Screens (top-level destinations)
// ---------------------------------------------------------------------------

/** What a contributed screen is handed: the backend and a way to pop back to the editor. */
interface ScreenContext {
    val backend: IdeBackend
    fun back()
}

/** A top-level screen reachable by [id] (e.g. from an action's `Navigate(id)` effect / `UiActionHost`). */
class ScreenContribution(
    val id: String,
    val title: String,
    val content: @Composable (ScreenContext) -> Unit,
)

object ScreenRegistry {
    private val items = mutableStateListOf<ScreenContribution>()

    fun register(screen: ScreenContribution): Registration {
        items.add(screen)
        return Registration { items.remove(screen) }
    }

    fun find(id: String): ScreenContribution? = items.firstOrNull { it.id == id }
    fun all(): List<ScreenContribution> = items.toList()
}

// ---------------------------------------------------------------------------
// Editor view modes (beyond Code / Blocks / Preview / Split)
// ---------------------------------------------------------------------------

/** What a contributed view mode renders against: the open file and its live buffer. */
interface ViewModeContext {
    val backend: IdeBackend
    val filePath: String
    val text: String
}

/** An editor view mode. [appliesTo] gates it per file (so a mode only offers for files it handles). */
class EditorViewModeContribution(
    val id: String,
    val label: String,
    val appliesTo: (filePath: String) -> Boolean = { true },
    val content: @Composable (ViewModeContext) -> Unit,
)

object ViewModeRegistry {
    private val items = mutableStateListOf<EditorViewModeContribution>()

    fun register(mode: EditorViewModeContribution): Registration {
        items.add(mode)
        return Registration { items.remove(mode) }
    }

    fun forFile(filePath: String): List<EditorViewModeContribution> = items.filter { it.appliesTo(filePath) }
    fun find(id: String): EditorViewModeContribution? = items.firstOrNull { it.id == id }
}
