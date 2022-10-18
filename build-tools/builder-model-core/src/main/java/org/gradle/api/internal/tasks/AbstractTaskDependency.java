package org.gradle.api.internal.tasks;

import org.gradle.api.Action;
import org.gradle.api.NonNullApi;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.transform.TransformationDependency;

import javax.annotation.Nullable;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.gradle.api.internal.tasks.WorkDependencyResolver.TASK_AS_TASK;

@NonNullApi
public abstract class AbstractTaskDependency implements TaskDependencyInternal {
    private static final WorkDependencyResolver<Task> IGNORE_ARTIFACT_TRANSFORM_RESOLVER = new WorkDependencyResolver<Task>() {
        @Override
        public boolean resolve(Task task, Object node, Action<? super Task> resolveAction) {
            // Ignore artifact transforms
            return node instanceof TransformationDependency || node instanceof WorkNodeAction;
        }
    };

    @Override
    public Set<? extends Task> getDependencies(@Nullable Task task) {
        CachingTaskDependencyResolveContext<Task> context = new CachingTaskDependencyResolveContext<Task>(
                asList(TASK_AS_TASK, IGNORE_ARTIFACT_TRANSFORM_RESOLVER)
        );
        return context.getDependencies(task, this);
    }
}