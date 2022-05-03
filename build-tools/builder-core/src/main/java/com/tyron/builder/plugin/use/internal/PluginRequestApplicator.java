package com.tyron.builder.plugin.use.internal;

import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.initialization.ScriptHandlerInternal;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.plugin.management.internal.PluginRequests;

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
