package com.tyron.builder.api;

import com.google.common.collect.Ordering;
import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.WorkDependencyResolver;
import com.tyron.builder.api.tasks.TaskProvider;

import org.junit.Test;

/**
 * WIP
 */
public class TestTaskExecution {

    @Test
    public void testTaskDependency() {
        DefaultTaskContainer container = new DefaultTaskContainer();
        container.register("task1", task -> {
            task.doLast(it -> System.out.println("Task 1"));
            task.dependsOn("task2");
        });

        container.register("task2", task -> {
            task.doLast(it -> System.out.println("Task 2"));
        });

        container.register("task3", task -> {
            task.doLast(it -> System.out.println("Task 3"));
            task.dependsOn("task1");
        });

        execute(container.resolveTask("task3"));
    }

    private void execute(Task task) {
        WorkDependencyResolver.TASK_AS_TASK
                .resolve(task, task.getTaskDependencies(), dependency -> {
                    if (task.equals(dependency)) {
                        throw new CircularDependencyException();
                    }
                    execute(dependency);
                });
        task.getActions().forEach(action -> action.execute(task));

        WorkDependencyResolver.TASK_AS_TASK
                .resolve(task, task.getMustRunAfter(), this::execute);
    }
}
