package com.tyron.builder.api.internal.classpath;

import com.tyron.builder.internal.classpath.ClassPath;

import javax.annotation.Nullable;

/**
 * A registry of dynamically loadable modules.
 */
public interface ModuleRegistry {
    /**
     * Locates an external module by name. An external module is one for which there is no meta-data available. Assumed to be packaged as a single jar file, and to have no runtime dependencies.
     *
     * @return the module. Does not return null.
     */
    Module getExternalModule(String name) throws UnknownModuleException;

    /**
     * Locates a module by name.
     *
     * @return the module. Does not return null.
     */
    Module getModule(String name) throws UnknownModuleException;

    /**
     * Tries to locate a module by name.
     *
     * @return the optional module, or {@literal null} if it cannot be found
     * @throws UnknownModuleException if the requested module is found but one of its dependencies is not
     */
    @Nullable
    Module findModule(String name) throws UnknownModuleException;

    /**
     * Returns the classpath used to search for modules, in addition to default locations in the Gradle distribution (if available). May be empty.
     */
    ClassPath getAdditionalClassPath();
}
