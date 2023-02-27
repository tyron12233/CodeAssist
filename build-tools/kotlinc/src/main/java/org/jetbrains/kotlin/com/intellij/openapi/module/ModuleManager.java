package org.jetbrains.kotlin.com.intellij.openapi.module;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.SimpleModificationTracker;
import org.jetbrains.kotlin.com.intellij.util.graph.Graph;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public abstract class ModuleManager extends SimpleModificationTracker {

    public static ModuleManager getInstance(Project project) {
        return project.getService(ModuleManager.class);
    }

    /**
     * Creates a module of the specified type at the specified path and adds it to the project
     * to which the module manager is related.
     *
     * @param filePath     path to an *.iml file where module configuration will be saved; name of the module will be equal to the file name without extension.
     * @param moduleTypeId the ID of the module type to create.
     * @return the module instance.
     */
    public abstract Module newModule(String filePath, String moduleTypeId);

    public Module newModule(Path filePath, String moduleTypeId) {
        return newModule(filePath.toString().replace(File.separatorChar, '/'), moduleTypeId);
    }

    /**
     * Loads a module from an .iml file with the specified path and adds it to the project.
     *
     * @param file the path to load the module from.
     * @return the module instance.
     * @throws IOException                 if an I/O error occurred when loading the module file.
     * @throws ModuleWithNameAlreadyExists if a module with such a name already exists in the project.
     */
    public abstract Module loadModule(String file) throws IOException;

    public abstract void disposeModule(Module module);

    public abstract Module[] getModules();

    @Nullable
    public abstract Module findModuleByName(String name);

    public abstract Module[] getSortedModules();

    public abstract Comparator<Module> getModuleDependencyComparator();

    /**
     * Returns the list of modules which directly depend on the specified module.
     *
     * @param module the module for which the list of dependent modules is requested.
     * @return list of *modules that depend on* given module.
     * @see ModuleUtilCore.getAllDependentModules
     */
    public abstract List<Module> getModuleDependentModules(Module module);

    public abstract boolean isModuleDependent(Module module);

    public abstract Graph<Module> getModuleGraph(boolean includeTests);

    public Iterable<? extends UnloadedModuleDescription> getUnloadedModuleDescriptions() {
        return Collections.emptyList();
    }
}
