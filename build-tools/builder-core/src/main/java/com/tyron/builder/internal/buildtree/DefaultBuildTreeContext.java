package com.tyron.builder.internal.buildtree;


import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.service.ServiceRegistry;

class DefaultBuildTreeContext implements BuildTreeContext {
    private final ServiceRegistry services;
    private boolean completed;

    public DefaultBuildTreeContext(ServiceRegistry services) {
        this.services = services;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action) {
        if (completed) {
            throw new IllegalStateException("Cannot run more than one action for a build tree.");
        }
        try {
            BuildTreeLifecycleListener broadcaster = services.get(ListenerManager.class).getBroadcaster(BuildTreeLifecycleListener.class);
            broadcaster.afterStart();
            try {
                return services.get(BuildTreeActionExecutor.class).execute(action, this);
            } finally {
                broadcaster.beforeStop();
            }
        } finally {
            completed = true;
        }
    }
}
