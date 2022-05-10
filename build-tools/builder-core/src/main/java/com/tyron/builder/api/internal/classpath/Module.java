package com.tyron.builder.api.internal.classpath;

import com.tyron.builder.internal.classpath.ClassPath;

import java.util.Set;

/**
 * Meta-data about a dynamically loadable module.
 */
public interface Module {
    /**
     * Returns the classpath for the module implementation. This is the classpath of the module itself. Does not include any dependencies.
     */
    ClassPath getImplementationClasspath();

    /**
     * Returns the classpath containing the runtime dependencies of the module. Does not include any other modules.
     */
    ClassPath getRuntimeClasspath();

    /**
     * Returns implementation + runtime.
     */
    ClassPath getClasspath();

    /**
     * Returns the modules required by this module.
     */
    Set<Module> getRequiredModules();

    /**
     * Returns the transitive closure of all modules required by this module, including the module itself.
     */
    Set<Module> getAllRequiredModules();

    /**
     * Returns the implementation + runtime classpath of the transitive closure of all modules required by this module, including the module itself.
     */
    ClassPath getAllRequiredModulesClasspath();
}
