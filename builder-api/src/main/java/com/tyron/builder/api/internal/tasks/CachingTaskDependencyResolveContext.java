package com.tyron.builder.api.internal.tasks;

import static java.lang.String.format;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.graph.CachingDirectedGraphWalker;
import com.tyron.builder.api.internal.graph.DirectedGraph;
import com.tyron.builder.api.tasks.TaskDependency;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Set;

import jdk.internal.util.Preconditions;

/**
 * <p>A {@link TaskDependencyResolveContext} which caches the dependencies for each {@link
 * TaskDependency} and {@link Buildable} instance during traversal of task
 * dependencies.</p>
 *
 */
public class CachingTaskDependencyResolveContext<T> extends AbstractTaskDependencyResolveContext {
    private final Deque<Object> queue = new ArrayDeque<Object>();
    private final CachingDirectedGraphWalker<Object, T> walker;
    private final Collection<? extends WorkDependencyResolver<T>> workResolvers;
    private Task task;

    public CachingTaskDependencyResolveContext(Collection<? extends WorkDependencyResolver<T>> workResolvers) {
        this.walker = new CachingDirectedGraphWalker<>(new TaskGraphImpl(workResolvers));
        this.workResolvers = workResolvers;
    }

    public Set<T> getDependencies(Task task, Object dependencies) {
        assert this.task == null;
        this.task = task;
        try {
            walker.add(dependencies);
            return walker.findValues();
        } catch (Exception e) {
            throw new TaskDependencyResolveException(format("Could not determine the dependencies of %s.", task), e);
        } finally {
            queue.clear();
            this.task = null;
        }
    }

    @Override
    public Task getTask() {
        return task;
    }

    @Override
    public void add(Object dependency) {
        assert dependency != null;
        if (dependency == TaskDependencyContainer.EMPTY) {
            // Ignore things we know are empty
            return;
        }
        queue.add(dependency);
    }

    private void attachFinalizerTo(T value, Action<? super Task> action) {
        for (WorkDependencyResolver<T> resolver : workResolvers) {
            if (resolver.attachActionTo(value, action)) {
                break;
            }
        }
    }

    private class TaskGraphImpl implements DirectedGraph<Object, T> {
        private final Collection<? extends WorkDependencyResolver<T>> workResolvers;

        public TaskGraphImpl(Collection<? extends WorkDependencyResolver<T>> workResolvers) {
            this.workResolvers = workResolvers;
        }

        @Override
        public void getNodeValues(Object node, final Collection<? super T> values, Collection<? super Object> connectedNodes) {
            if (node instanceof TaskDependencyContainer) {
                TaskDependencyContainer taskDependency = (TaskDependencyContainer) node;
                queue.clear();
                taskDependency.visitDependencies(CachingTaskDependencyResolveContext.this);
                connectedNodes.addAll(queue);
                return;
            }
//            if (node instanceof Buildable) {
//                Buildable buildable = (Buildable) node;
//                connectedNodes.add(buildable.getBuildDependencies());
//                return;
//            }
//            if (node instanceof FinalizeAction) {
//                FinalizeAction finalizeAction = (FinalizeAction) node;
//                TaskDependencyContainer dependencies = finalizeAction.getDependencies();
//                Set<T> deps = new CachingTaskDependencyResolveContext<T>(workResolvers).getDependencies(task, dependencies);
//                for (T dep : deps) {
//                    attachFinalizerTo(dep, finalizeAction);
//                    values.add(dep);
//                }
//                return;
//            }
            for (WorkDependencyResolver<T> workResolver : workResolvers) {
                if (workResolver.resolve(task, node, values::add)) {
                    return;
                }
            }
            throw new IllegalArgumentException(
                    format(
                            "Cannot resolve object of unknown type %s to a Task.",
                            node.getClass().getSimpleName()
                    )
            );
        }
    }
}