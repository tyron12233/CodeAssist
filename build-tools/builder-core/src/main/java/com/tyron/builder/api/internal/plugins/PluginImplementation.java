package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.plugin.use.PluginId;

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
