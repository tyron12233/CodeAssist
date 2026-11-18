package dev.ide.core.services

import dev.ide.build.RunSettings
import dev.ide.core.EngineContext
import dev.ide.model.Module

/** The published [RunSettings] over the engine's own project preference ([mainClassOverride]). */
internal class RunSettingsService(private val engine: EngineContext) : RunSettings {

    override fun entryPoint(module: Module): String? = engine.mainClassOverride(module)
}
