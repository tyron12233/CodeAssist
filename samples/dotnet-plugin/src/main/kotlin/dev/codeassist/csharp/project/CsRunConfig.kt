package dev.codeassist.csharp.project

import dev.codeassist.csharp.editor.CsWorkspace
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The entry point the user chose for a module on its Run card in Module Settings.
 *
 * Asked of the host through `RunSettings` (SPI 3.7.0) once a workspace has been seen. A host older than that
 * has no such service, and there the value is read from where the IDE stores it:
 * `<project>/.platform/settings.properties`, under `module.mainClass.<module id>`. That file is the IDE's own,
 * so if an old host kept it elsewhere the override is not found and Run starts the first `Main`.
 */
internal object CsRunConfig {

    /** Returns the configured entry point for [module], or null when none is set. */
    fun entryPoint(module: Module, moduleDir: Path): String? {
        val settings = CsWorkspace.entryPoints ?: return mainClassOverride(module.id.value, moduleDir)
        return runCatching { settings(module) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Maximum number of parent directories searched for `.platform`. */
    private const val LEVELS = 6

    /** Reads the entry point for [moduleId] from the IDE's settings file, or null when none is set. */
    fun mainClassOverride(moduleId: String, moduleDir: Path): String? {
        var dir: Path? = moduleDir.toAbsolutePath().normalize()

        repeat(LEVELS) {
            val current = dir ?: return null
            val file = current.resolve(".platform/settings.properties")

            if (Files.isRegularFile(file)) {
                return runCatching {
                    Properties().apply {
                        Files.newBufferedReader(file).use { load(it) }
                    }
                        .getProperty("module.mainClass.$moduleId")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            }

            dir = current.parent
        }

        return null
    }
}