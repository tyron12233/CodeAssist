package com.tyron.builder.plugin.management;

import javax.annotation.Nullable;

/**
 * Allows plugin resolution rules to inspect a requested plugin and modify which
 * target plugin will be used.
 *
 * @since 3.5
 */
public interface PluginResolveDetails {

    /**
     * Get the plugin that was requested.
     */
    PluginRequest getRequested();

    /**
     * Sets the implementation module to use for this plugin.
     *
     * @param notation the module to use, supports the same notations as {@link org.gradle.api.artifacts.dsl.DependencyHandler}
     */
    void useModule(Object notation);

    /**
     * Sets the version of the plugin to use.
     *
     * @param version version to use
     */
    void useVersion(@Nullable String version);

    /**
     * The target plugin request to use.
     */
    PluginRequest getTarget();

}
