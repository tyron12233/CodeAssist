package dev.ide.build

import dev.ide.model.Module
import dev.ide.platform.ServiceKey

/**
 * What the user has configured about running a module, in Module Settings.
 *
 * The Run card there is offered for every module that is not an Android app or library, a plugin's module
 * types among them, and stores one value: the entry point to start. What that names is the module type's
 * own business (a class with a `main` for a JVM module, a type with `Main` for a C# one), so it is handed
 * over as the text the user chose.
 *
 * WORKSPACE-scoped; resolve it from the workspace a callback hands you (`workspace.service(RUN_SETTINGS)`).
 * Since SPI 3.7.0; on an older host the key resolves to nothing.
 */
interface RunSettings {

    /** The entry point the user chose for [module], or null when it is left to be detected. */
    fun entryPoint(module: Module): String?
}

/** WORKSPACE-scoped [RunSettings]. */
val RUN_SETTINGS = ServiceKey<RunSettings>("platform.runSettings")
