package org.gradle.plugin.use;

import org.gradle.api.Incubating;
import org.gradle.api.artifacts.VersionConstraint;

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
