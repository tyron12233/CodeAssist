package org.gradle.api.internal;

import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.tasks.TaskInputs;

public interface TaskInputsInternal extends TaskInputs, TaskDependencyContainer {

    /**
     * Calls the corresponding visitor methods for all inputs added via the runtime API.
     */
    void visitRegisteredProperties(PropertyVisitor visitor);
}