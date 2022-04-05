package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.tasks.DefaultTaskDependency;

public interface TaskDependencyFactory {
    DefaultTaskDependency configurableDependency();
}