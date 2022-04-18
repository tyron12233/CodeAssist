package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.tasks.TaskInputs;

public interface TaskInputsInternal extends TaskInputs, TaskDependencyContainer {

    /**
     * Calls the corresponding visitor methods for all inputs added via the runtime API.
     */
    void visitRegisteredProperties(PropertyVisitor visitor);
}