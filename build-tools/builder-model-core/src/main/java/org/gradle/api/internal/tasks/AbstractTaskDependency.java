package org.gradle.api.internal.tasks;

import static org.gradle.api.internal.tasks.WorkDependencyResolver.TASK_AS_TASK;

import static java.util.Arrays.asList;

import org.gradle.api.Action;
import org.gradle.api.NonNullApi;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.transform.TransformationDependency;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

@NonNullApi
public abstract class AbstractTaskDependency implements TaskDependencyInternal {
    private static final WorkDependencyResolver<Task> IGNORE_ARTIFACT_TRANSFORM_RESOLVER = new WorkDependencyResolver<Task>() {
        @Override
        public boolean resolve(Task task, Object node, Action<? super Task> resolveAction) {
            // Ignore artifact transforms
            return node instanceof TransformationDependency;
        }

        @Override
        public boolean attachActionTo(Task task, Action<? super Task> action) {
            return false;
        }
    };

    @Override
    public Set<? extends Task> getDependencies(@Nullable Task task) {
        CachingTaskDependencyResolveContext<Task> context = new CachingTaskDependencyResolveContext<>(
                asList(TASK_AS_TASK, IGNORE_ARTIFACT_TRANSFORM_RESOLVER)
        );
        return context.getDependencies(task, this);
    }
}
