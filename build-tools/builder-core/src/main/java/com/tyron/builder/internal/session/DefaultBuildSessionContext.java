package com.tyron.builder.internal.session;

import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.buildtree.BuildActionRunner;

class DefaultBuildSessionContext implements BuildSessionContext {
    private final ServiceRegistry sessionScopeServices;
    private boolean completed;

    public DefaultBuildSessionContext(ServiceRegistry sessionScopeServices) {
        this.sessionScopeServices = sessionScopeServices;
    }

    @Override
    public ServiceRegistry getServices() {
        return sessionScopeServices;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action) {
        if (completed) {
            throw new IllegalStateException("Cannot run more than one action for a session.");
        }
        try {
            BuildSessionLifecycleListener sessionLifecycleListener = sessionScopeServices.get(
                    ListenerManager.class).getBroadcaster(BuildSessionLifecycleListener.class);
            sessionLifecycleListener.afterStart();
            try {
                return sessionScopeServices.get(BuildSessionActionExecutor.class).execute(action, this);
            } finally {
                sessionLifecycleListener.beforeComplete();
            }
        } finally {
            completed = true;
        }
    }
}