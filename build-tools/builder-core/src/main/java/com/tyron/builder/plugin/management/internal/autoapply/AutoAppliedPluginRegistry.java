package com.tyron.builder.plugin.management.internal.autoapply;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.plugin.management.internal.PluginRequests;

/**
 * Provides a list of plugins that can be auto-applied to a certain Project.
 *
 * @since 4.3
 */
public interface AutoAppliedPluginRegistry {

    /**
     * Returns the plugins that should be auto-applied to the given
     * target, based on the current build invocation.
     */
    PluginRequests getAutoAppliedPlugins(BuildProject target);

    PluginRequests getAutoAppliedPlugins(Settings target);
}
