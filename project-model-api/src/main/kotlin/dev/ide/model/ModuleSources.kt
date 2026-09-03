package dev.ide.model

import dev.ide.platform.ServiceKey
import java.nio.file.Path

/**
 * A module's source-set and source-root layout: the slice of the engine's module service a plugin can name.
 * WORKSPACE-scoped.
 *
 * The engine's own service also edits build features, compiler plugins, packaging options and toolchain
 * warnings, all phrased in the module-config types the IDE's UI port owns, which are not plugin API. The
 * layout half is phrased in this module's own types, so it promotes unchanged, and it is the half a plugin
 * that generates code needs: a declared place to put what it writes.
 */
interface ModuleSources {

    /** [module]'s source-set names, in declaration order. */
    fun sourceSetNamesOf(module: Module): List<String>

    /**
     * The directory [sourceSetName]'s roots live under (`src/main`, say): the parent of its first content
     * root, or `<moduleRoot>/src/<name>` when the set is empty or absent. New roots go here. Null only when
     * the module's own directory cannot be resolved.
     */
    fun sourceSetBaseFor(module: Module, sourceSetName: String): Path?

    /**
     * Create `<set-base>/[dirName]` and register it as a content root of [sourceSetName] carrying [roles].
     * Returns the created directory, or null when the module or its project cannot be resolved.
     */
    fun addSourceRoot(
        moduleName: String,
        sourceSetName: String,
        dirName: String,
        roles: Set<ContentRole>,
    ): Path?

    /**
     * Drop the content root at [dirRelPath] (relative to the module directory) from [sourceSetName].
     * Model-only: the directory on disk is left untouched. True when the model changed.
     */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, dirRelPath: String): Boolean

    /** Create an empty source set [name] on [moduleName]. False when it already has one by that name. */
    fun addSourceSet(moduleName: String, name: String): Boolean
}

/** WORKSPACE-scoped [ModuleSources] for the open project. */
val MODULE_SOURCES = ServiceKey<ModuleSources>("platform.moduleSources")
