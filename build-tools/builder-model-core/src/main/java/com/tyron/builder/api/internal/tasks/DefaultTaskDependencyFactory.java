package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.tasks.TaskResolver;

import org.jetbrains.annotations.Nullable;

public class DefaultTaskDependencyFactory implements TaskDependencyFactory {
    @Nullable
    private final TaskResolver taskResolver;

    public static TaskDependencyFactory withNoAssociatedProject() {
        return new DefaultTaskDependencyFactory(null);
    }

    public static TaskDependencyFactory forProject(TaskResolver taskResolver) {
        return new DefaultTaskDependencyFactory(taskResolver);
    }

    private DefaultTaskDependencyFactory(@Nullable TaskResolver taskResolver) {
        this.taskResolver = taskResolver;
    }

    @Override
    public DefaultTaskDependency configurableDependency() {
        return new DefaultTaskDependency(taskResolver);
    }
}
