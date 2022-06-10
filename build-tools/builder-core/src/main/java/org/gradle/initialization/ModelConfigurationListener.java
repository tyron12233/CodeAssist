package org.gradle.initialization;


import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface ModelConfigurationListener {
    /**
     * Invoked when the model has been configured successfully. This listener should not do any further configuration.
     */
    void onConfigure(GradleInternal model);
}