package org.gradle.execution.plan;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public class TaskDependencyResolver {
    private final List<DependencyResolver> dependencyResolvers;
    private CachingTaskDependencyResolveContext<Node> context;

    public TaskDependencyResolver(List<DependencyResolver> dependencyResolvers) {
        this.dependencyResolvers = dependencyResolvers;
        this.context = createTaskDependencyResolverContext(dependencyResolvers);
    }

    public void clear() {
        context = createTaskDependencyResolverContext(dependencyResolvers);
    }

    private static CachingTaskDependencyResolveContext<Node> createTaskDependencyResolverContext(List<DependencyResolver> workResolvers) {
        return new CachingTaskDependencyResolveContext<>(workResolvers);
    }

    public Set<Node> resolveDependenciesFor(@Nullable TaskInternal task, Object dependencies) {
        return context.getDependencies(task, dependencies);
    }
}