package org.gradle.internal.session;

import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.buildtree.BuildActionRunner;

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