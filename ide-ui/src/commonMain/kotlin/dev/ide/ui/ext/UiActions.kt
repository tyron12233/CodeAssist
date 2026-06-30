package dev.ide.ui.ext

import androidx.compose.runtime.mutableStateListOf
import dev.ide.ui.backend.IdeBackend

/**
 * The UI side of the hybrid action model. Engine-side actions (toolbar/menu/palette commands that are pure
 * engine operations, or third-party dex plugins) cross the boundary as data through `IdeBackend.actionsFor` /
 * `invokeAction`. But some actions act on the **running UI** itself: navigate to a screen, toggle the theme,
 * open a file. Those can't be expressed as neutral engine data, so they live here, in a Compose-side registry
 * the app and in-UI plugins contribute to. The UI surfaces (the "More" menu, the command palette) render both
 * sources together.
 *
 * Modeled on the one already-extensible UI piece, `TreeIcons`: a process-global, Compose-observable registry.
 * It will move into a dedicated `ide-ui-api` module when a plugin needs to compile against it standalone
 * (Phase C); the contract here is the same one that module will expose.
 */

/** Teardown handle for a registration. ide-ui keeps its own (it must not depend on platform-core). */
fun interface Registration {
    fun dispose()
}

/**
 * The capabilities a [UiHostAction] uses when invoked. Supplied by the app shell at invocation time (it holds
 * the navigation callbacks and the backend), so an action is a static contribution while the host varies with
 * the current screen.
 */
interface UiActionHost {
    val backend: IdeBackend

    /** Open a named UI destination (see [UiDestinations]). Forward-compatible with the screen registry. */
    fun navigate(destination: String)

    /** Flip the app theme (light/dark). */
    fun toggleTheme()

    /** Open [path] in the editor, optionally moving the caret to [offset]. */
    fun openFile(path: String, offset: Int = 0)

    /** Surface a transient status message, if the host shows them. */
    fun message(text: String) {}
}

/** The built-in navigation destinations a [UiActionHost.navigate] understands. Open set. */
object UiDestinations {
    /** The Settings & Tools hub — the single entry to the global settings/tools (global settings, code style,
     *  SDK manager, keystore manager). Also reachable from the project picker. */
    const val HUB = "hub"
    const val SETTINGS = "settings"
    const val MODULES = "modules"
    const val SDK = "sdk"
    const val KEYSTORES = "keystores"
    const val LOGS = "logs"
    const val PROJECTS = "projects"
    const val DEPENDENCIES = "dependencies"
    const val CODE_STYLE = "codeStyle"
}

/**
 * A UI-side action. Parallels the engine-side `IdeAction` (plugin-api) but its [perform] receives a
 * [UiActionHost] so it can drive navigation/theme/app state. [places] reuses the `UiActionPlaces` ids.
 * [description] is an optional subtitle for list-style menus (the "More" sheet renders it).
 */
interface UiHostAction {
    val id: String
    val text: String
    val description: String? get() = null
    val iconId: String? get() = null
    val places: Set<String>
    val order: Int get() = 1000
    fun isVisible(host: UiActionHost): Boolean = true
    fun perform(host: UiActionHost)
}

/** A concise [UiHostAction] backed by a lambda. */
class SimpleUiAction(
    override val id: String,
    override val text: String,
    override val places: Set<String>,
    override val description: String? = null,
    override val iconId: String? = null,
    override val order: Int = 1000,
    private val visible: (UiActionHost) -> Boolean = { true },
    private val onPerform: (UiActionHost) -> Unit,
) : UiHostAction {
    override fun isVisible(host: UiActionHost): Boolean = visible(host)
    override fun perform(host: UiActionHost) = onPerform(host)
}

/** The process-global registry of UI-side actions. Compose-observable: a registration recomposes any surface
 *  reading it. The app registers built-ins (see `BuiltInUiActions`); in-UI plugins add their own. */
object UiActionRegistry {
    private val actions = mutableStateListOf<UiHostAction>()

    fun register(action: UiHostAction): Registration {
        actions.add(action)
        return Registration { actions.remove(action) }
    }

    /** The visible actions for [place], ordered by ([UiHostAction.order], then [UiHostAction.text]). */
    fun forPlace(place: String, host: UiActionHost): List<UiHostAction> =
        actions
            .filter { place in it.places && it.isVisible(host) }
            .sortedWith(compareBy({ it.order }, { it.text }))
}
