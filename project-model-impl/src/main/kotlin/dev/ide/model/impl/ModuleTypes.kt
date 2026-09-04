package dev.ide.model.impl

/** Compatibility aliases; both moved to `project-model-api` (see [dev.ide.model.ModuleTypeRegistry]). */
@Deprecated(
    "Moved to project-model-api",
    ReplaceWith("ModuleTypeRegistry", "dev.ide.model.ModuleTypeRegistry"),
)
typealias ModuleTypeRegistry = dev.ide.model.ModuleTypeRegistry

@Deprecated("Moved to project-model-api", ReplaceWith("UnknownModuleType", "dev.ide.model.UnknownModuleType"))
typealias UnknownModuleType = dev.ide.model.UnknownModuleType
