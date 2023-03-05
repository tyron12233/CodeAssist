package org.gradle.tooling.internal.provider.action;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.invocation.BuildAction;

public class ExecuteBuildAction implements BuildAction {
    private final StartParameterInternal startParameter;

    public ExecuteBuildAction(StartParameterInternal startParameter) {
        this.startParameter = startParameter;
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    @Override
    public boolean isRunTasks() {
        return true;
    }

    @Override
    public boolean isCreateModel() {
        return false;
    }
}
