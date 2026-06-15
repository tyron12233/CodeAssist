package dev.ide.model

import dev.ide.platform.ExtensionPoint

/**
 * The `platform.moduleType` extension point. Plugins (`java-support`,
 * `android-support`, …) contribute their [ModuleType]s here. The model uses it to resolve a persisted
 * module-type id (e.g. `"java-lib"`) back to the registered [ModuleType] on load, without the core
 * knowing any concrete type.
 */
val ModuleTypeExtensionPoint: ExtensionPoint<ModuleType> = ExtensionPoint("platform.moduleType")
