package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.tasks.BuildOperationContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionMode;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

import java.util.Optional;

public class DefaultTaskExecutionContext implements TaskExecutionContext {

    private final LocalTaskNode localTaskNode;
    private final TaskProperties properties;
    private final WorkValidationContext validationContext;
    private final ValidationAction validationAction;
    private TaskExecutionMode taskExecutionMode;
    private BuildOperationContext snapshotTaskInputsBuildOperationContext;

    public DefaultTaskExecutionContext(LocalTaskNode localTaskNode, TaskProperties taskProperties, WorkValidationContext validationContext, ValidationAction validationAction) {
        this.localTaskNode = localTaskNode;
        this.properties = taskProperties;
        this.validationContext = validationContext;
        this.validationAction = validationAction;
    }

    @Override
    public LocalTaskNode getLocalTaskNode() {
        return localTaskNode;
    }

    @Override
    public TaskExecutionMode getTaskExecutionMode() {
        return taskExecutionMode;
    }

    @Override
    public WorkValidationContext getValidationContext() {
        return validationContext;
    }

    @Override
    public ValidationAction getValidationAction() {
        return validationAction;
    }

    @Override
    public void setTaskExecutionMode(TaskExecutionMode taskExecutionMode) {
        this.taskExecutionMode = taskExecutionMode;
    }

    @Override
    public TaskProperties getTaskProperties() {
        return properties;
    }

    @Override
    public Optional<BuildOperationContext> removeSnapshotTaskInputsBuildOperationContext() {
        Optional<BuildOperationContext> result = Optional.ofNullable(snapshotTaskInputsBuildOperationContext);
        snapshotTaskInputsBuildOperationContext = null;
        return result;
    }

    @Override
    public void setSnapshotTaskInputsBuildOperationContext(BuildOperationContext snapshotTaskInputsBuildOperation) {
        this.snapshotTaskInputsBuildOperationContext = snapshotTaskInputsBuildOperation;
    }
}
