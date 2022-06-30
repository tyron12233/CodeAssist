package com.tyron.builder.internal.buildtree;

import com.google.common.base.Joiner;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.api.internal.StartParameterInternal;

public class RunTasksRequirements implements BuildActionModelRequirements {
    private final StartParameterInternal startParameter;

    public RunTasksRequirements(StartParameterInternal startParameter) {
        this.startParameter = startParameter;
    }

    @Override
    public boolean isRunsTasks() {
        return true;
    }

    @Override
    public boolean isCreatesModel() {
        return false;
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    @Override
    public DisplayName getActionDisplayName() {
        return Describables.of("calculating task graph");
    }

    @Override
    public DisplayName getConfigurationCacheKeyDisplayName() {
        return Describables.of("tasks:", Joiner.on(" ").join(startParameter.getTaskNames()));
    }

    @Override
    public void appendKeyTo(Hasher hasher) {
        // Identify the type of action
        hasher.putByte((byte) 1);
    }
}