package org.gradle.api.internal.tasks;

import static org.gradle.api.internal.tasks.WorkDependencyResolver.TASK_AS_TASK;

import org.gradle.api.Task;

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
