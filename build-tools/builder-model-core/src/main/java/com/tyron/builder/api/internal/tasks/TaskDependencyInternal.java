package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskDependency;

import java.util.Collections;
import java.util.Set;

public interface TaskDependencyInternal extends TaskDependency, TaskDependencyContainer {

    TaskDependency EMPTY = new TaskDependencyInternal() {
        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {

        }

        @Override
        public Set<? extends Task> getDependencies(Task task) {
            return Collections.emptySet();
        }
    };
}
