package dev.ide.model

import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Resolves persisted module-type ids back to the [ModuleType]s plugins contributed to
 * [ModuleTypeExtensionPoint]. A persisted id that no plugin provides resolves to [UnknownModuleType]
 * (with no source-set/facet templates) rather than failing the load — the model can still be inspected
 * and the missing plugin reported.
 *
 * A plugin contributing a module type of its own registers on the extension point through its
 * `PluginRegistration`, so the contribution is attributed to it and removed when it unloads; this registry is
 * the read side the host resolves against, and reads through to the extension registry on every lookup.
 */
class ModuleTypeRegistry(private val extensions: ExtensionRegistry) {
    fun register(type: ModuleType, plugin: PluginId) = extensions.register(ModuleTypeExtensionPoint, type, plugin)

    fun byId(id: String): ModuleType? =
        extensions.extensions(ModuleTypeExtensionPoint).firstOrNull { it.id == id }

    /** Every module type contributed to the extension point, in registration order (for the New-Module picker). */
    fun all(): List<ModuleType> = extensions.extensions(ModuleTypeExtensionPoint)

    fun resolve(id: String): ModuleType = byId(id) ?: UnknownModuleType(id)
}

/** Stand-in for a persisted module-type id no registered plugin claims. Contributes no defaults. */
class UnknownModuleType(override val id: String) : ModuleType {
    override val displayName: String get() = "Unknown module type ($id)"
    override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = emptySet()
}
