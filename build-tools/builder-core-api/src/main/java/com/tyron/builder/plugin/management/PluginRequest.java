package com.tyron.builder.plugin.management;

import com.tyron.builder.api.artifacts.ModuleVersionSelector;
import com.tyron.builder.internal.HasInternalProtocol;
import com.tyron.builder.plugin.use.PluginId;

import javax.annotation.Nullable;

/**
 * Contains information about a plugin that has been requested for resolution.
 *
 * @since 3.5
 */
@HasInternalProtocol
public interface PluginRequest {

    /**
     * The ID of the plugin requested. Never null.
     */
    PluginId getId();

    /**
     * The version of the plugin if one was specified, otherwise null.
     */
    @Nullable
    String getVersion();

    /**
     * The implementation module of the plugin if one was explicitly specified, otherwise null.
     */
    @Nullable
    ModuleVersionSelector getModule();
}
