package com.tyron.builder.initialization;


import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface ModelConfigurationListener {
    /**
     * Invoked when the model has been configured successfully. This listener should not do any further configuration.
     */
    void onConfigure(GradleInternal model);
}