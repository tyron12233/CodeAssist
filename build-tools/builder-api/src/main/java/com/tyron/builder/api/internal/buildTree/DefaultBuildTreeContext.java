package com.tyron.builder.api.internal.buildTree;


import com.tyron.builder.api.internal.invocation.BuildAction;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;

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
//            BuildTreeLifecycleListener broadcaster = services.get(ListenerManager.class).getBroadcaster(BuildTreeLifecycleListener.class);
//            broadcaster.afterStart();
            try {
                return services.get(BuildTreeActionExecutor.class).execute(action, this);
            } finally {
//                broadcaster.beforeStop();
            }
        } finally {
            completed = true;
        }
    }
}
