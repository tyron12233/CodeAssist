package com.tyron.builder.plugin.use;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.artifacts.VersionConstraint;

/**
 * A plugin dependency.
 *
 * @since 7.2
 */
@Incubating
public interface PluginDependency {
    String getPluginId();

    VersionConstraint getVersion();
}
