package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public abstract class ModuleRootManager implements ModuleRootModel {

    /**
     * Returns the module root manager instance for the specified module.
     *
     * @param module the module for which the root manager is requested.
     * @return the root manager instance.
     */
    public static ModuleRootManager getInstance(@NonNull Module module) {
        return module.getComponent(ModuleRootManager.class);
    }

    /**
     * Returns the file index for the current module.
     *
     * @return the file index instance.
     */
    @NotNull
    public abstract ModuleFileIndex getFileIndex();

    /**
     * Returns the list of modules on which the current module directly depends. The method does not traverse
     * the entire dependency structure - dependencies of dependency modules are not included in the returned list.
     *
     * @return the array of module direct dependencies.
     */
    public abstract Module[] getDependencies();

    /**
     * Returns the list of modules on which the current module directly depends. The method does not traverse
     * the entire dependency structure - dependencies of dependency modules are not included in the returned list.
     *
     * @param includeTests whether test-only dependencies should be included
     * @return the array of module direct dependencies.
     */
    public abstract Module[] getDependencies(boolean includeTests);

    /**
     * Checks if the current module directly depends on the specified module.
     *
     * @param module the module to check.
     * @return true if {@code module} is contained in the list of dependencies for the current module, false otherwise.
     */
    public abstract boolean isDependsOn(@NonNull Module module);

}
