package com.tyron.builder.api.internal.tasks;

import static com.tyron.builder.api.internal.tasks.WorkDependencyResolver.TASK_AS_TASK;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskDependencyInternal;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

public abstract class AbstractTaskDependency implements TaskDependencyInternal {

    @Override
    public Set<? extends Task> getDependencies(Task task) {
        CachingTaskDependencyResolveContext<Task> context = new CachingTaskDependencyResolveContext<>(
                Collections.singletonList(TASK_AS_TASK)
        );
        return context.getDependencies(task, this);
    }
}
