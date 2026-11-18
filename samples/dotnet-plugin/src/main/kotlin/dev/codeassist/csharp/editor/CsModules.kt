package dev.codeassist.csharp.editor

import dev.ide.model.Module

/**
 * The modules this plugin has been handed, by folder, so a file path can be traced to its module.
 * The editor's decoration pass gets only a path, and a run mark needs the module's name (the Run row's id carries it).
 * Modules are remembered when the diagnostics or the Run picker see them.
 */
internal object CsModules {
    private val byDir = LinkedHashMap<String, Module>()

    @Synchronized
    fun remember(module: Module) {
        byDir[module.dir.path.trimEnd('/')] = module
    }

    /** The module owning path: the longest remembered folder that contains it. */
    @Synchronized
    fun forPath(path: String): Module? =
        byDir.entries.filter {
            path.startsWith(it.key + "/")

        }.maxByOrNull {
            it.key.length
        }?.value
}