package com.tyron.builder.plugin.management;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * Allows modification of {@link PluginRequest}s before they are resolved.
 *
 * @since 3.5
 */
@HasInternalProtocol
public interface PluginResolutionStrategy {

    /**
     * Adds an action that is executed for each plugin that is resolved.
     * The {@link PluginResolveDetails} parameter contains information about
     * the plugin that was requested and allows the rule to modify which plugin
     * will actually be resolved.
     */
    void eachPlugin(Action<? super PluginResolveDetails> rule);

}
