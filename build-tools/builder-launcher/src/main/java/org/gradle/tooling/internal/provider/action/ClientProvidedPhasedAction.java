package org.gradle.tooling.internal.provider.action;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.tooling.internal.provider.serialization.SerializedPayload;

public class ClientProvidedPhasedAction extends SubscribableBuildAction {
    private final StartParameterInternal startParameter;
    private final SerializedPayload phasedAction;
    private final boolean runTasks;

    public ClientProvidedPhasedAction(StartParameterInternal startParameter, SerializedPayload phasedAction, boolean runTasks, BuildEventSubscriptions clientSubscriptions) {
        super(clientSubscriptions);
        this.startParameter = startParameter;
        this.phasedAction = phasedAction;
        this.runTasks = runTasks;
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    public SerializedPayload getPhasedAction() {
        return phasedAction;
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
