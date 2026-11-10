package dev.ide.android.layouteditor

import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.ui.EditorPreview
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The engine facet: the plugin's identity. The editor needs nothing registered on the engine side, because
 * everything it asks of the project (rendering, the layout model) the host already offers as services; what it
 * adds is UI, in [LayoutEditorUiPlugin]. Declaring Android support as a dependency means disabling that also
 * disables this, instead of leaving a design surface with nothing to render.
 */
class LayoutEditorPlugin : Plugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "Layout Editor",
        description = "Design Android XML layouts visually: a live render you can select, inspect and edit, " +
            "with the component tree and every attribute the view accepts.",
        dependsOn = listOf("android-support"),
    )

    override fun register(reg: PluginRegistration) {
        reg.logger("layout-editor").info("layout editor ready")
    }

    companion object {
        const val ID: String = "android-layout-editor"
    }
}

/**
 * The UI facet: claims Android layout files for the editor's Preview/Split surface and draws the design editor
 * there. The pane edits the same buffer the code view shows, through the preview context.
 */
object LayoutEditorUiPlugin : UiPlugin {
    override val id: String = LayoutEditorPlugin.ID

    override fun contribute(ui: UiRegistration) {
        ui.editorPreview(
            EditorPreview(
                id = "$id.design",
                title = "Design",
                appliesTo = ::isLayoutFile,
                content = { ctx -> LayoutDesigner(ctx) },
            ),
        )
    }
}

/** An Android layout resource: an `.xml` under a `res/layout` (or qualified `res/layout-*`) folder. */
fun isLayoutFile(path: String): Boolean {
    val p = path.replace('\\', '/')
    if (!p.endsWith(".xml")) return false
    val folder = p.substringBeforeLast('/').substringAfterLast('/')
    return (folder == "layout" || folder.startsWith("layout-")) && p.contains("/res/")
}
