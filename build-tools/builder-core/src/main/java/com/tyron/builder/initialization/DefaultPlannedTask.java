package com.tyron.builder.initialization;

import com.tyron.builder.internal.taskgraph.CalculateTaskGraphBuildOperationType;

import java.util.List;

public class DefaultPlannedTask implements CalculateTaskGraphBuildOperationType.PlannedTask {
    private final CalculateTaskGraphBuildOperationType.TaskIdentity taskIdentity;
    private final List<CalculateTaskGraphBuildOperationType.TaskIdentity> dependencies;
    private final List<CalculateTaskGraphBuildOperationType.TaskIdentity> mustRunAfter;
    private final List<CalculateTaskGraphBuildOperationType.TaskIdentity> shouldRunAfter;
    private final List<CalculateTaskGraphBuildOperationType.TaskIdentity> finalizers;

    public DefaultPlannedTask(CalculateTaskGraphBuildOperationType.TaskIdentity taskIdentity,
                              List<CalculateTaskGraphBuildOperationType.TaskIdentity> dependencies,
                              List<CalculateTaskGraphBuildOperationType.TaskIdentity> mustRunAfter,
                              List<CalculateTaskGraphBuildOperationType.TaskIdentity> shouldRunAfter,
                              List<CalculateTaskGraphBuildOperationType.TaskIdentity> finalizers) {
        this.taskIdentity = taskIdentity;
        this.dependencies = dependencies;
        this.mustRunAfter = mustRunAfter;
        this.shouldRunAfter = shouldRunAfter;
        this.finalizers = finalizers;
    }

    @Override
    public CalculateTaskGraphBuildOperationType.TaskIdentity getTask() {
        return taskIdentity;
    }

    @Override
    public List<CalculateTaskGraphBuildOperationType.TaskIdentity> getDependencies() {
        return dependencies;
    }

    @Override
    public List<CalculateTaskGraphBuildOperationType.TaskIdentity> getMustRunAfter() {
        return mustRunAfter;
    }

    @Override
    public List<CalculateTaskGraphBuildOperationType.TaskIdentity> getShouldRunAfter() {
        return shouldRunAfter;
    }

    @Override
    public List<CalculateTaskGraphBuildOperationType.TaskIdentity> getFinalizedBy() {
        return finalizers;
    }
}