package com.tyron.builder.tooling.internal.provider.action;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.invocation.BuildAction;

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
