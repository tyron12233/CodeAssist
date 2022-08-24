package org.gradle.api.internal.tasks;

import com.google.common.base.Preconditions;
import org.gradle.api.Buildable;
import org.gradle.api.NonNullApi;
import org.gradle.api.Task;
import org.gradle.internal.graph.CachingDirectedGraphWalker;
import org.gradle.internal.graph.DirectedGraph;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Set;

import static java.lang.String.format;

/**
 * <p>A {@link TaskDependencyResolveContext} which caches the dependencies for each {@link
 * org.gradle.api.tasks.TaskDependency} and {@link org.gradle.api.Buildable} instance during traversal of task
 * dependencies.</p>
 *
 * <p>Supported types:</p> <ul>
 *
 * <li>{@link org.gradle.api.Task}</li>
 *
 * <li>{@link org.gradle.api.tasks.TaskDependency}</li>
 *
 * <li>{@link org.gradle.api.internal.tasks.TaskDependencyInternal}</li>
 *
 * <li>{@link org.gradle.api.Buildable}</li>
 *
 * </ul>
 */
@NonNullApi
public class CachingTaskDependencyResolveContext<T> extends AbstractTaskDependencyResolveContext {
    private final Deque<Object> queue = new ArrayDeque<Object>();
    private final CachingDirectedGraphWalker<Object, T> walker;
    private Task task;

    public CachingTaskDependencyResolveContext(Collection<? extends WorkDependencyResolver<T>> workResolvers) {
        this.walker = new CachingDirectedGraphWalker<>(new TaskGraphImpl(workResolvers));
    }

    public Set<T> getDependencies(@Nullable Task task, Object dependencies) {
        Preconditions.checkState(this.task == null);
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
    @Nullable
    public Task getTask() {
        return task;
    }

    @Override
    public void add(Object dependency) {
        Preconditions.checkNotNull(dependency);
        if (dependency == TaskDependencyContainer.EMPTY) {
            // Ignore things we know are empty
            return;
        }
        queue.add(dependency);
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
            if (node instanceof Buildable) {
                Buildable buildable = (Buildable) node;
                connectedNodes.add(buildable.getBuildDependencies());
                return;
            }
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