package com.tyron.builder.api.internal;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface BuildScopeListenerRegistrationListener {

    /**
     * Called when registering a build scope listener.
     */
    void onBuildScopeListenerRegistration(Object listener, String invocationDescription, Object invocationSource);
}
