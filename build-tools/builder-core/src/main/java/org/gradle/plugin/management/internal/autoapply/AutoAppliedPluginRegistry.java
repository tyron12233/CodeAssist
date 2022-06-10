package org.gradle.plugin.management.internal.autoapply;

import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.plugin.management.internal.PluginRequests;

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
    PluginRequests getAutoAppliedPlugins(Project target);

    PluginRequests getAutoAppliedPlugins(Settings target);
}
