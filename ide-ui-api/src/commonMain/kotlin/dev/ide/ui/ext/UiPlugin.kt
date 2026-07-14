package dev.ide.ui.ext

import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons

/**
 * The single surface a UI plugin contributes its Compose-bearing UI through — unifying what used to be four
 * separate process-global registries (UI actions, tool windows, screens, editor view modes) behind one scope.
 *
 * This is the Compose-side counterpart of the engine plugin model (plugin-api's `Plugin`, which contributes
 * data-driven extensions + services): contributions here render their own `@Composable` bodies, so they can't
 * cross the neutral `IdeBackend` boundary as data (the deliberate hybrid). Each method returns a
 * [Registration] the plugin's unload disposes.
 */
interface UiContributionScope {
    /** Attribution id of the contributing plugin (parallels a `PluginId`; a plain string here since this layer
     *  stays free of the platform-core dependency — the platform-core-attributed bridge is a later step). */
    val pluginId: String

    fun action(action: UiHostAction): Registration
    fun toolWindow(toolWindow: ToolWindowContribution): Registration
    fun screen(screen: ScreenContribution): Registration
    fun viewMode(mode: EditorViewModeContribution): Registration

    /** Register (or override) the file-tree icon for [iconId]. Tree icons are a persistent lookup, so the
     *  returned handle is a no-op today (nothing unregisters an icon). */
    fun treeIcon(iconId: String, icon: TreeIcon): Registration
}

/**
 * A plugin that contributes Compose-bearing UI. Parallels plugin-api's `Plugin`; loaded once per process by
 * [UiPluginHost] (the UI is a single process-global surface, unlike the per-project engine the `PluginManager`
 * drives, so UI contributions register once rather than per opened project).
 */
interface UiPlugin {
    val id: String
    fun contributeUi(scope: UiContributionScope)
}

/**
 * Loads [UiPlugin]s onto the process-global UI registries exactly once. The IDE's built-in UI
 * ([BuiltInUiPlugin]) is always present; a host registers additional UI plugins before [ensureLoaded]. This
 * replaces the ad-hoc `BuiltInUiActions.ensureRegistered()` with a uniform, plugin-driven load, and is the
 * seam a future engine-plugin↔UI bridge (platform-core-attributed) plugs into.
 */
object UiPluginHost {
    private val plugins = mutableListOf<UiPlugin>(BuiltInUiPlugin)
    private var loaded = false

    /** Register an additional UI plugin. Call before [ensureLoaded]; a plugin added after is loaded on the
     *  next (still-once) load only if nothing has loaded yet. */
    fun register(plugin: UiPlugin) {
        if (plugins.none { it.id == plugin.id }) plugins.add(plugin)
    }

    /** Contribute every registered UI plugin's UI, once per process (idempotent). */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        for (p in plugins) p.contributeUi(Scope(p.id))
    }

    private class Scope(override val pluginId: String) : UiContributionScope {
        override fun action(action: UiHostAction): Registration = UiActionRegistry.register(action)
        override fun toolWindow(toolWindow: ToolWindowContribution): Registration =
            ToolWindowRegistry.register(toolWindow)
        override fun screen(screen: ScreenContribution): Registration = ScreenRegistry.register(screen)
        override fun viewMode(mode: EditorViewModeContribution): Registration = ViewModeRegistry.register(mode)
        override fun treeIcon(iconId: String, icon: TreeIcon): Registration {
            TreeIcons.register(iconId, icon)
            return Registration {}
        }
    }
}
