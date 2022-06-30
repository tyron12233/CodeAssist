package com.tyron.builder.api.internal.classpath;

import java.util.Set;

/**
 * A registry of dynamically loaded plugin modules.
 */
public interface PluginModuleRegistry {
    /**
     * Plugin modules exposed in the Gradle API.
     */
    Set<Module> getApiModules();

    /**
     * Plugin modules exposed to the Gradle runtime only.
     */
    Set<Module> getImplementationModules();
}
