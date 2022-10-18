package org.gradle.plugin.use.internal;

import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.initialization.ScriptHandlerInternal;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.plugin.management.internal.PluginRequests;

import javax.annotation.Nullable;

@ServiceScope(Scopes.Build.class)
public interface PluginRequestApplicator {

    /**
     * Resolves the given {@link PluginRequests} into the given {@link ScriptHandlerInternal#getScriptClassPath()},
     * exports the resulting classpath into the given {@link ClassLoaderScope}, closes it and then applies
     * the requested plugins.
     *
     * A null target indicates that no plugin requests should be resolved but only the setup of the given
     * {@link ClassLoaderScope}.
     */
    void applyPlugins(PluginRequests requests, ScriptHandlerInternal scriptHandler, @Nullable PluginManagerInternal target, ClassLoaderScope classLoaderScope);
}
