package org.gradle.tooling.internal.provider.action;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.tooling.internal.protocol.ModelIdentifier;

public class BuildModelAction extends SubscribableBuildAction {
    private final StartParameterInternal startParameter;
    private final String modelName;
    private final boolean runTasks;

    public BuildModelAction(StartParameterInternal startParameter, String modelName, boolean runTasks, BuildEventSubscriptions clientSubscriptions) {
        super(clientSubscriptions);
        this.startParameter = startParameter;
        this.modelName = modelName;
        this.runTasks = runTasks;
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    public String getModelName() {
        return modelName;
    }

    @Override
    public boolean isRunTasks() {
        return runTasks;
    }

    @Override
    public boolean isCreateModel() {
        return !ModelIdentifier.NULL_MODEL.equals(modelName);
    }
}
