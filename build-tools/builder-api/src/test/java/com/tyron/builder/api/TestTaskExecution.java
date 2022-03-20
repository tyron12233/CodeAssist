package com.tyron.builder.api;

import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WIP
 */
public class TestTaskExecution {

    private DefaultTaskContainer container;

    @Before
    public void setup() {
        container = new DefaultTaskContainer();
    }

    @Test
    public void testTaskDependency() {
        List<Task> executedTasks = new ArrayList<>();

        container.register("task1", task -> {
            task.doLast(executedTasks::add);
            task.dependsOn("task2");
        });

        container.register("task2", task -> {
            task.doLast(executedTasks::add);
        });

        container.register("task3", task -> {
            task.doLast(executedTasks::add);
            task.dependsOn("task1");
        });

        TaskExecutor taskExecutor = new TaskExecutor(container);
        taskExecutor.execute("task3");

        // task 3 -> task 1 -> task 2
        assertExecutionOrder(executedTasks, "task2", "task1", "task3");
    }

    @Test
    public void testNoCircularDependency() {
        container.register("task1", task -> {
            task.dependsOn("task2");
        });
        container.register("task2", task -> {
            task.dependsOn("task1");
        });
        try {
            TaskExecutor taskExecutor = new TaskExecutor(container);
            taskExecutor.execute("task1");
        } catch (CircularDependencyException expected) {
            return;
        }

        throw new AssertionError("Circular dependency was not detected.");
    }

    private void assertExecutionOrder(List<Task> tasks, String... executionOrder) {
        if (tasks.size() != executionOrder.length) {
            throw new AssertionError("Expected " + executionOrder.length + " but got " + tasks.size());
        }

        for (int i = 0; i < executionOrder.length; i++) {
            Task task = tasks.get(i);
            String name = executionOrder[i];

            if (!task.getName().equals(name)) {
                throw new AssertionError("Execution order not met. Tasks ran: " + tasks + "\n" +
                                         "Expected order: " + Arrays.toString(executionOrder));
            }
        }
    }

}
