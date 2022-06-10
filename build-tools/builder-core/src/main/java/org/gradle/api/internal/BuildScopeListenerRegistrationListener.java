package org.gradle.api.internal;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface BuildScopeListenerRegistrationListener {

    /**
     * Called when registering a build scope listener.
     */
    void onBuildScopeListenerRegistration(Object listener, String invocationDescription, Object invocationSource);
}
