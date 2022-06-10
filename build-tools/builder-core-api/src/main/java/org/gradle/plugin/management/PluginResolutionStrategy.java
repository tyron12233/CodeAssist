package org.gradle.plugin.management;

import org.gradle.api.Action;
import org.gradle.internal.HasInternalProtocol;

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
