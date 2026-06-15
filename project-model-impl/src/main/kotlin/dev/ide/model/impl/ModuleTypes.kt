package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Resolves persisted module-type ids back to the [ModuleType]s plugins contributed to
 * [ModuleTypeExtensionPoint]. A persisted id that no plugin provides resolves to [UnknownModuleType]
 * (with no source-set/facet templates) rather than failing the load — the model can still be inspected
 * and the missing plugin reported.
 */
class ModuleTypeRegistry(private val extensions: ExtensionRegistry) {
    fun register(type: ModuleType, plugin: PluginId) = extensions.register(ModuleTypeExtensionPoint, type, plugin)

    fun byId(id: String): ModuleType? =
        extensions.extensions(ModuleTypeExtensionPoint).firstOrNull { it.id == id }

    fun resolve(id: String): ModuleType = byId(id) ?: UnknownModuleType(id)
}

class UnknownModuleType(override val id: String) : ModuleType {
    override val displayName: String get() = "Unknown module type ($id)"
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = emptySet()
}
