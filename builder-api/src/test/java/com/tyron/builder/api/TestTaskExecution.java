package com.tyron.builder.api;

import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.TaskExecutor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TestTaskExecution {

    /**
     * A dependency of a task should not depend back into the task
     */
    @Test
    public void testCircularDependency() {
        DefaultTask firstTask = new DefaultTask();
        DefaultTask secondTask = new DefaultTask();

        firstTask.dependsOn(secondTask);
        secondTask.dependsOn(firstTask);
        try {
            new TaskExecutor().runTask(firstTask);
        } catch (CircularDependencyException expected) {
            return;
        }
        throw new AssertionError("Circular dependency was not detected.");
    }

    /**
     * A task should not be able to depend on itself
     */
    @Test
    public void testSelfDependency() {
        DefaultTask firstTask = new DefaultTask();
        firstTask.dependsOn(firstTask);
        try {
            new TaskExecutor().runTask(firstTask);
        } catch (CircularDependencyException expected) {
            return;
        }
        throw new AssertionError("Circular dependency was not detected.");
    }

    /**
     * A task with deep dependencies
     */
    @Test
    public void testComplexDependency() {
        DefaultTask firstTask = new DefaultTask();
        firstTask.doFirst(it -> System.out.println("First"));

        DefaultTask current = firstTask;
        for (int i = 0; i < 10; i++) {
            final int pos = i;
            DefaultTask newTask = new DefaultTask();
            newTask.doFirst(it -> System.out.println("Task #" + pos));
            newTask.dependsOn(current);
            current = newTask;
        }

        new TaskExecutor().runTask(current);
    }

    @Test
    public void test() {
        List<Task> taskRan = new ArrayList<>();

        DefaultTask defaultTask = new DefaultTask();
        defaultTask.doFirst(taskRan::add);
        defaultTask.setDescription("defaultTask");

        Task task = new DefaultTask();
        task.doFirst(taskRan::add);
        task.dependsOn(defaultTask);
        task.setDescription("task");

        defaultTask.mustRunAfter(task);

        TaskExecutor taskExecutor = new TaskExecutor();
        taskExecutor.runTask(task);

        System.out.println(taskRan);
    }


}
