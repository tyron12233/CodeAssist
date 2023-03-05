package org.gradle.api.internal.plugins;

import org.gradle.internal.DisplayName;
import org.gradle.plugin.use.PluginId;

import javax.annotation.Nullable;

public interface PluginImplementation<T> extends PotentialPlugin<T> {
    DisplayName getDisplayName();

    /**
     * An id for the plugin implementation, if known.
     */
    @Nullable
    PluginId getPluginId();

    boolean isAlsoKnownAs(PluginId id);
}
