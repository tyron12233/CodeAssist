package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import dev.ide.ui.backend.FileActions
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

    /** Platform bridges a panel may need: opening an external link, sharing or picking a file. */
    val fileActions: FileActions get() = FileActions.None

    /** Navigate to a contributed [ScreenContribution] by id, for a panel whose detail view is a full screen. */
    fun openScreen(id: String) {}

    /** Open [path] in the editor with the caret at [offset], for a panel that lists code the user can jump
     *  to. Defaulted to a no-op so a host that has no editor to open into (a preview, a test) needs nothing. */
    fun openFile(path: String, offset: Int = 0) {}
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

    /** Navigate to a contributed [ScreenContribution] by id, for an overlay whose answer is a whole screen. */
    fun openScreen(id: String) {}

    /** Open [path] in the editor with the caret at [offset]. See [ToolWindowContext.openFile]. */
    fun openFile(path: String, offset: Int = 0) {}
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

/** What a contributed screen is handed: the backend, platform bridges, and a way to pop back. */
interface ScreenContext {
    val backend: IdeBackend

    /** Platform bridges a screen may need: opening an external link, sharing or picking a file. */
    val fileActions: FileActions get() = FileActions.None

    fun back()

    /** Navigate to another contributed screen, replacing this one. */
    fun openScreen(id: String) {}

    /** Open [path] in the editor with the caret at [offset]. See [ToolWindowContext.openFile]. */
    fun openFile(path: String, offset: Int = 0) {}
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
// Editor preview panes (the Preview / Split surface for a file kind)
// ---------------------------------------------------------------------------

/** What an editor-preview body is handed: the file, its live buffer, the surface's scheme, and where to
 *  report problems. Mirrors what the built-in Compose/layout/resource panes already receive. */
interface EditorPreviewContext {
    val backend: IdeBackend
    val path: String

    /** The editor's live buffer for [path]. A preview follows the buffer, not the file on disk. */
    val text: String

    /** Whether the preview surface is showing its dark scheme. */
    val dark: Boolean

    /** Problems to show in the shared chip above the pane; an empty list clears them. */
    fun reportProblems(problems: List<String>)

    /** Open [path] in the editor with the caret at [offset]. See [ToolWindowContext.openFile]. */
    fun openFile(path: String, offset: Int = 0) {}

    /** Navigate to a contributed [ScreenContribution] by id. */
    fun openScreen(id: String) {}
}

/**
 * A preview pane for the files [appliesTo] claims. The built-in panes (Compose, layout, resource, Markdown)
 * are still chosen first; this is what the editor falls back to before defaulting to the Compose pane, and
 * what makes "preview" available for a file kind the IDE itself knows nothing about.
 */
class EditorPreviewContribution(
    val id: String,
    val title: String,
    val appliesTo: (String) -> Boolean,
    val content: @Composable (EditorPreviewContext) -> Unit,
)

object EditorPreviewRegistry {
    private val items = mutableStateListOf<EditorPreviewContribution>()

    fun register(preview: EditorPreviewContribution): Registration {
        items.add(preview)
        return Registration { items.remove(preview) }
    }

    /** The first contributed pane claiming [path], or null. Registration order decides, so a built-in UI
     *  plugin's pane wins over a later-loaded plugin's, matching how the other registries resolve. A
     *  contributor whose predicate throws is skipped rather than allowed to break the editor. */
    fun forPath(path: String): EditorPreviewContribution? =
        items.firstOrNull { runCatching { it.appliesTo(path) }.getOrDefault(false) }

    fun all(): List<EditorPreviewContribution> = items.toList()
}

// ---------------------------------------------------------------------------
// Editor view modes (beyond Code / Blocks / Preview / Split)
// ---------------------------------------------------------------------------

/**
 * What a contributed view mode renders against: the open file, its live buffer, and the one operation a view
 * of a buffer needs that it cannot do itself.
 */
interface ViewModeContext {
    val backend: IdeBackend
    val filePath: String

    /** The editor's live buffer, which may differ from disk. Recomposes the pane as the user types. */
    val text: String

    /** The caret offset in the shared buffer, so a pane can follow the code editor's position. */
    val caretOffset: Int get() = 0

    /**
     * Replace `[start, end)` in the shared buffer with [newText].
     *
     * A view mode is a view OF the tab's buffer, not a copy of it: the code editor, the block editor and this
     * pane all edit the one [dev.ide.ui.editor.core.EditorSession], which is why an edit made here is
     * undoable, is picked up by analysis, and marks the tab dirty exactly as typing does. Replacing the whole
     * text works but costs the user their undo granularity, so prefer the narrowest range that changes.
     */
    fun replaceText(start: Int, end: Int, newText: String) {}

    /** Open [path] in the editor with the caret at [offset]. See [ToolWindowContext.openFile]. */
    fun openFile(path: String, offset: Int = 0) {}
}

/**
 * An editor view mode: a surface for a tab beside Code, Blocks, Preview and Split.
 *
 * [appliesTo] gates it per file, so a mode only offers itself for files it handles, and [iconId] is the glyph
 * the toggle shows (an id in the IDE's registry: the toggle is icon-only, and [label] rides along as the
 * accessibility description). [order] places it among the other contributed modes; the built-ins always come
 * first.
 */
class EditorViewModeContribution(
    val id: String,
    val label: String,
    val iconId: String = "layers",
    val order: Int = 1000,
    val appliesTo: (filePath: String) -> Boolean = { true },

    /**
     * Whether a tab for [filePath] should OPEN in this mode rather than in the code editor.
     *
     * This is how a plugin comes to own a file kind: claim it here and the file opens into this pane, the way
     * an image already opens into Preview. Code stays reachable from the toggle on purpose, and that is not a
     * hole in the ownership: a pane can be wrong about a file, and a user who cannot see the text has no way
     * to find out why.
     *
     * A pane that owns a kind the text editor cannot represent (a binary) must read the file itself, through
     * its own engine facet: the tab's buffer is a text decode of the bytes, and for a binary it is garbage.
     */
    val isDefault: (filePath: String) -> Boolean = { false },
    val content: @Composable (ViewModeContext) -> Unit,
)

/** The process-global registry of contributed view modes. Compose-observable, like its siblings here. */
object ViewModeRegistry {
    private val ordering = compareBy<EditorViewModeContribution>({ it.order }, { it.id })
    private val items = mutableStateListOf<EditorViewModeContribution>()

    fun register(mode: EditorViewModeContribution): Registration {
        val after = items.indexOfFirst { ordering.compare(it, mode) > 0 }
        items.add(if (after < 0) items.size else after, mode)
        return Registration { items.remove(mode) }
    }

    /** The modes offering themselves for [filePath], in resolution order. A throwing predicate is skipped. */
    fun forFile(filePath: String): List<EditorViewModeContribution> =
        items.filter { runCatching { it.appliesTo(filePath) }.getOrDefault(false) }

    fun find(id: String): EditorViewModeContribution? = items.firstOrNull { it.id == id }

    /**
     * The mode a tab for [filePath] should open in, or null to open in the code editor.
     *
     * The first claimant in resolution order wins, so a plugin registered earlier (or with a lower `order`)
     * keeps the file kind. A throwing predicate is skipped rather than allowed to break opening a file, which
     * is the one place in this registry where a failure would be user-visible as "the file will not open".
     */
    fun defaultFor(filePath: String): EditorViewModeContribution? =
        items.firstOrNull {
            runCatching { it.isDefault(filePath) && it.appliesTo(filePath) }.getOrDefault(false)
        }

    fun all(): List<EditorViewModeContribution> = items.toList()
}
