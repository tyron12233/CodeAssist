package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

import java.util.Optional;

public interface TaskExecutionContext {

    LocalTaskNode getLocalTaskNode();

    TaskExecutionMode getTaskExecutionMode();

    WorkValidationContext getValidationContext();

    ValidationAction getValidationAction();

    void setTaskExecutionMode(TaskExecutionMode taskExecutionMode);

    TaskProperties getTaskProperties();

    /**
     * Gets and clears the context of the build operation designed to measure the time taken
     * by capturing input snapshotting and cache key calculation.
     */
    Optional<BuildOperationContext> removeSnapshotTaskInputsBuildOperationContext();

    /**
     * Sets the context for the build operation designed to measure the time taken
     * by capturing input snapshotting and cache key calculation.
     */
    void setSnapshotTaskInputsBuildOperationContext(BuildOperationContext operation);

    interface ValidationAction {
        void validate(boolean historyMaintained, TypeValidationContext validationContext);
    }
}