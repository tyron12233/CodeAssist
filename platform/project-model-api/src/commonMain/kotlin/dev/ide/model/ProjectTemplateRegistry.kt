package dev.ide.model

import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.ProjectTemplateExtensionPoint
import dev.ide.model.template.TemplateId
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Resolves the [ProjectTemplate]s plugins contributed to [ProjectTemplateExtensionPoint]: the
 * Create-Project gallery's source of truth. Mirrors [ModuleTypeRegistry] over its own extension point.
 *
 * One template per id, the first registered (built-ins load before installed plugins). Two plugins can both
 * contribute an id, a plugin in development reusing one a published plugin already ships, say, and the gallery
 * keyed on ids crashed on the second. The host reports the one left out against its plugin.
 */
class ProjectTemplateRegistry(private val extensions: ExtensionRegistry) {
    fun register(template: ProjectTemplate, plugin: PluginId) =
        extensions.register(ProjectTemplateExtensionPoint, template, plugin)

    fun all(): List<ProjectTemplate> = extensions.extensions(ProjectTemplateExtensionPoint).distinctBy { it.id }

    fun byId(id: TemplateId): ProjectTemplate? = all().firstOrNull { it.id == id }
}
