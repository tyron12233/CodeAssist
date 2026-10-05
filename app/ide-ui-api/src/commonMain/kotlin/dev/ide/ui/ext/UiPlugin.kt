package dev.ide.ui.ext

import dev.ide.ui.icons.PluginFileIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.colors.ColorAttribute
import dev.ide.ui.theme.colors.ColorAttributes

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
    fun overlay(overlay: OverlayContribution): Registration

    /** Claim an open editor tab's status dot for a state of this plugin's own ([TabDecorationContribution]).
     *  The dot is host-drawn, so this contribution supplies data, not a `@Composable` body. */
    fun tabDecoration(decoration: TabDecorationContribution): Registration

    /** Register (or override) the file-tree icon for [iconId]. Tree icons are a persistent lookup, so the
     *  returned handle is a no-op today (nothing unregisters an icon). */
    fun treeIcon(iconId: String, icon: TreeIcon): Registration

    /** Teach the editor how to color, comment, and indent a language ([EditorLanguageProfile]). This is the
     *  text-level layer; a language wanting parsing and resolution also registers a `LanguageBackend`. */
    fun editorLanguage(profile: EditorLanguageProfile): Registration

    /**
     * Add a colorable thing to the editor's color model ([ColorAttribute]), so the user can set its color
     * and font style in Settings and a scheme can carry it.
     *
     * A language contributes its own constructs here and points its token types at them through
     * [EditorLanguageProfile.tokenColorKeys]. Because an attribute declares a `parent` and its own defaults,
     * it looks right in every scheme — including one a user built before the attribute existed — and a
     * scheme that recolors the parent moves it along until the user says otherwise.
     */
    fun colorAttribute(attribute: ColorAttribute): Registration

    /** Register the art AND the name mapping for a file type's icon ([TreeIcons] + [PluginFileIcons]). */
    fun fileIcon(iconId: String, suffixes: List<String>, icon: TreeIcon): Registration

    /** Add a preview pane for a file kind ([EditorPreviewContribution]): the Preview/Split surface for
     *  something the IDE's four built-in panes do not cover. */
    fun editorPreview(preview: EditorPreviewContribution): Registration

    /** Place composables at document positions in the code editor ([EditorLayerContribution]): a hover card,
     *  an inline button, a code-lens row. For marks that are data, use `platform.editorDecoration` instead. */
    fun editorLayer(layer: EditorLayerContribution): Registration

    /** Draw straight into the editor's canvas ([EditorPainterContribution]). The escape hatch for a plugin
     *  whose colors are its own rather than one of the theme's roles; held to a per-frame budget. */
    fun editorPainter(painter: EditorPainterContribution): Registration
}

/**
 * A plugin that contributes Compose-bearing UI. Parallels plugin-api's `Plugin` — the Compose-side facet of a
 * feature whose engine facet is an engine `Plugin`. Both the engine `PluginManager` and [UiPluginHost] are
 * process-global and load once, so the two facets share a lifetime; they stay separate objects only because a
 * `@Composable` body can't live in the engine module (nor cross the neutral `IdeBackend` boundary as data).
 *
 * A built-in feature co-declares its two facets in `ide-core`'s `BuiltInPlugins` (a `BuiltInPlugin(engine, ui)`
 * pair). The engine facet's enabled state gates BOTH: only enabled plugins' UI facets are handed to the shell
 * (via `IdeBackend.uiPlugins`) and registered here, so a disabled plugin contributes no UI — no per-plugin
 * gating in the UI layer.
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
     *  next (still-once) load only if nothing has loaded yet. The shell registers `IdeBackend.uiPlugins`, which
     *  is already filtered to enabled plugins by the engine's `PluginCatalog` — so a disabled plugin never
     *  reaches here. */
    fun register(plugin: UiPlugin) {
        if (plugins.none { it.id == plugin.id }) plugins.add(plugin)
    }

    private val failed = LinkedHashMap<String, Throwable>()

    /**
     * Contribute every registered UI plugin's UI, once per process (idempotent).
     *
     * Each plugin is isolated: one whose `contributeUi` throws has whatever it registered before the throw
     * withdrawn, is recorded in [failures], and the rest still load. This runs during app startup, so letting
     * the throw escape would crash every launch; the usual cause is an installed plugin built against an
     * older host, failing with a `LinkageError` (`NoSuchMethodError`) rather than an exception, so the catch
     * covers every [Throwable].
     */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        for (p in plugins) contribute(p)
    }

    /**
     * Contribute [plugin]'s UI now, isolated: if its `contributeUi` throws, everything it registered before the
     * throw is withdrawn and the failure is recorded in [failures]. Returns whether it contributed cleanly.
     */
    fun contribute(plugin: UiPlugin): Boolean {
        val scope = Scope(plugin.id)
        return try {
            plugin.contributeUi(scope)
            failed.remove(plugin.id)
            true
        } catch (t: Throwable) {
            scope.withdraw()
            failed[plugin.id] = t
            false
        }
    }

    /** The UI plugins whose `contributeUi` threw during [ensureLoaded], by plugin id, with what they threw. */
    val failures: Map<String, Throwable> get() = failed

    /** Records each registration so a plugin that fails part-way can be withdrawn whole. */
    private class Scope(override val pluginId: String) : UiContributionScope {
        private val registrations = ArrayList<Registration>()

        private fun track(registration: Registration): Registration = registration.also { registrations += it }

        fun withdraw() {
            registrations.asReversed().forEach { runCatching { it.dispose() } }
            registrations.clear()
        }

        override fun action(action: UiHostAction): Registration = track(UiActionRegistry.register(action))
        override fun toolWindow(toolWindow: ToolWindowContribution): Registration =
            track(ToolWindowRegistry.register(toolWindow))
        override fun screen(screen: ScreenContribution): Registration = track(ScreenRegistry.register(screen))
        override fun viewMode(mode: EditorViewModeContribution): Registration = track(ViewModeRegistry.register(mode))
        override fun overlay(overlay: OverlayContribution): Registration = track(OverlayRegistry.register(overlay))
        override fun tabDecoration(decoration: TabDecorationContribution): Registration =
            track(TabDecorationRegistry.register(decoration))
        override fun treeIcon(iconId: String, icon: TreeIcon): Registration {
            TreeIcons.register(iconId, icon)
            return Registration {}
        }

        override fun editorLanguage(profile: EditorLanguageProfile): Registration =
            track(EditorLanguageRegistry.register(profile))

        override fun colorAttribute(attribute: ColorAttribute): Registration =
            track(ColorAttributes.register(attribute))

        override fun fileIcon(iconId: String, suffixes: List<String>, icon: TreeIcon): Registration {
            TreeIcons.register(iconId, icon)
            // Only the name mapping is undone: TreeIcons is a persistent lookup with nothing to unregister,
            // exactly like treeIcon above.
            return track(PluginFileIcons.register(suffixes, iconId))
        }

        override fun editorPreview(preview: EditorPreviewContribution): Registration =
            track(EditorPreviewRegistry.register(preview))

        override fun editorLayer(layer: EditorLayerContribution): Registration =
            track(EditorLayerRegistry.register(layer))

        override fun editorPainter(painter: EditorPainterContribution): Registration =
            track(EditorPainterRegistry.register(painter))
    }
}
