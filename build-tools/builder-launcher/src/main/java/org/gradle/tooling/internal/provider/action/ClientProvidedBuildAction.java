package org.gradle.tooling.internal.provider.action;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.tooling.internal.provider.serialization.SerializedPayload;

public class ClientProvidedBuildAction extends SubscribableBuildAction {
    private final StartParameterInternal startParameter;
    private final SerializedPayload action;
    private final boolean runTasks;

    public ClientProvidedBuildAction(StartParameterInternal startParameter, SerializedPayload action, boolean runTasks, BuildEventSubscriptions clientSubscriptions) {
        super(clientSubscriptions);
        this.startParameter = startParameter;
        this.action = action;
        this.runTasks = runTasks;
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    public SerializedPayload getAction() {
        return action;
    }

    @Override
    public boolean isRunTasks() {
        return runTasks;
    }

    @Override
    public boolean isCreateModel() {
        return true;
    }
}
