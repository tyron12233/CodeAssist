package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class FailureCollectingTaskDependencyResolveContext implements TaskDependencyResolveContext {
    private final Set<Object> seen = new HashSet<Object>();
    private final TaskDependencyResolveContext context;
    private final Set<Throwable> failures = new LinkedHashSet<Throwable>();

    public FailureCollectingTaskDependencyResolveContext(TaskDependencyResolveContext context) {
        this.context = context;
    }

    public Set<Throwable> getFailures() {
        return failures;
    }

    @Override
    public void add(Object dep) {
        if (!seen.add(dep)) {
            return;
        }
        if (dep instanceof TaskDependencyContainer) {
            TaskDependencyContainer container = (TaskDependencyContainer) dep;
            container.visitDependencies(this);
        } else {
            context.add(dep);
        }
    }

    @Override
    public void visitFailure(Throwable failure) {
        failures.add(failure);
    }

    @Override
    public Task getTask() {
        return context.getTask();
    }
}
