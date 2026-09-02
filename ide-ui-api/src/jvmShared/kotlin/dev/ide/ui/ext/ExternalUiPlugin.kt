package dev.ide.ui.ext

import androidx.compose.runtime.remember
import dev.ide.plugin.ui.Overlay
import dev.ide.plugin.ui.Screen
import dev.ide.plugin.ui.ScreenUiContext
import dev.ide.plugin.ui.ToolWindow
import dev.ide.plugin.ui.ToolWindowAnchor as ExternalAnchor
import dev.ide.plugin.ui.UiContext
import dev.ide.plugin.ui.UiHandle
import dev.ide.plugin.ui.UiPlugin as ExternalUiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * Adapts a plugin's UI facet ([ExternalUiPlugin], from the published `plugin-ui-api`) onto the internal
 * contribution model, so an installed plugin's tool windows, screens and overlays land in the same registries
 * the built-in UI plugins use and the host renders them without knowing where they came from.
 *
 * The adaptation exists because the two models are deliberately different. Internally a body is handed the
 * whole `IdeBackend`; publishing that would freeze every concern service and DTO in it as plugin API. The
 * published surface is `UiContext` instead: where the user is, plus the few operations a panel cannot perform
 * itself. Everything else a plugin needs it reaches through its **engine** facet, which shares its
 * classloader, so the two halves of one plugin are ordinary Kotlin to each other.
 *
 * Ids pass through verbatim rather than being namespaced by plugin id, because an engine-side action's
 * `ActionEffect.Navigate(id)` has to name the same screen the UI facet registered. Two plugins claiming one
 * id is the same collision two built-ins would have.
 *
 * Lives in `jvmShared` (the desktop + android targets, not `commonMain`) because `plugin-ui-api` is a plain
 * JVM artifact: a plugin ships as a JVM/Android APK, so there is nothing for the other targets to load.
 */
fun ExternalUiPlugin.asUiPlugin(pluginId: String): UiPlugin = BridgedUiPlugin(pluginId, this)

private class BridgedUiPlugin(
    override val id: String,
    private val delegate: ExternalUiPlugin,
) : UiPlugin {

    override fun contributeUi(scope: UiContributionScope) {
        delegate.contribute(BridgedRegistration(id, scope))
    }
}

private class BridgedRegistration(
    override val pluginId: String,
    private val scope: UiContributionScope,
) : UiRegistration {

    override fun toolWindow(toolWindow: ToolWindow): UiHandle {
        val registration = scope.toolWindow(
            ToolWindowContribution(
                id = toolWindow.id,
                title = toolWindow.title,
                iconId = toolWindow.iconId,
                anchor = toolWindow.anchor.internal(),
                order = toolWindow.order,
            ) { ctx ->
                // Remembered per context instance: the host re-creates one when the file or the navigation
                // handles change, and the body should not see a new object on every recomposition.
                toolWindow.content(remember(ctx) { ToolWindowUiContext(ctx) })
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun screen(screen: Screen): UiHandle {
        val registration = scope.screen(
            ScreenContribution(id = screen.id, title = screen.title) { ctx ->
                screen.content(remember(ctx) { HostScreenUiContext(ctx) })
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun overlay(overlay: Overlay): UiHandle {
        val registration = scope.overlay(
            OverlayContribution(id = overlay.id) { ctx ->
                overlay.content(remember(ctx) { OverlayUiContext(ctx) })
            },
        )
        return UiHandle { registration.dispose() }
    }
}

private fun ExternalAnchor.internal(): ToolWindowAnchor = when (this) {
    ExternalAnchor.LEFT -> ToolWindowAnchor.LEFT
    ExternalAnchor.RIGHT -> ToolWindowAnchor.RIGHT
    ExternalAnchor.BOTTOM -> ToolWindowAnchor.BOTTOM
}

/** The open project's root, or null when none is open (the picker reports an empty path). */
private fun projectPathOf(backend: dev.ide.ui.backend.IdeBackend): String? =
    runCatching { backend.project.rootPath }.getOrNull()?.takeIf { it.isNotEmpty() }

private class ToolWindowUiContext(private val ctx: ToolWindowContext) : UiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)
    override val activeFilePath: String? get() = ctx.activeFilePath
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)
}

private class OverlayUiContext(private val ctx: OverlayContext) : UiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** An overlay is app-wide, not tied to a tab; the host hands it no file. */
    override val activeFilePath: String? get() = null
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)
}

private class HostScreenUiContext(private val ctx: ScreenContext) : ScreenUiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** A contributed screen replaces the editor rather than sitting beside it, so there is no active tab
     *  from its point of view. */
    override val activeFilePath: String? get() = null
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)
    override fun back() = ctx.back()
}
