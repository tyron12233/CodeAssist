package org.gradle.api.internal.tasks;

import org.gradle.execution.plan.LocalTaskNode;
import org.gradle.api.internal.changedetection.TaskExecutionMode;
import org.gradle.internal.execution.WorkValidationContext;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.TaskProperties;

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