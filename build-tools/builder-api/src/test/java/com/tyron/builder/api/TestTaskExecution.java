package com.tyron.builder.api;

import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.tasks.TaskContainer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * WIP
 */
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
            new TaskExecutor().run(firstTask);
        } catch (Throwable expected) {
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
            new TaskExecutor().run(firstTask);
        } catch (Throwable expected) {
            return;
        }
        throw new AssertionError("Circular dependency was not detected.");
    }

    /**
     * A task with deep dependencies
     */
    @Test
    public void testComplexDependency() {
        TaskContainer taskContainer = new TaskContainer();

        Action<Task> action = task -> {
            System.out.println("-" + task.getDescription());
        };

        DefaultTask firstTask = new DefaultTask();
        firstTask.doFirst(action);
        taskContainer.registerTask(firstTask);

        DefaultTask current = firstTask;
        for (int i = 0; i < 10; i++) {
            DefaultTask newTask = new DefaultTask();
            newTask.doFirst(action);
            newTask.setDescription("Task #" + i);
            newTask.dependsOn(current);
            taskContainer.registerTask(newTask);

            // new task = 5
            // execution:
            // #5 -> Sub sub task -> Sub Task

            if (i == 5) {
                DefaultTask subTask = new DefaultTask();
                subTask.setDescription("Sub Task");
                subTask.doFirst(action);
                subTask.mustRunAfter(newTask);

                DefaultTask subsubTask = new DefaultTask();
                subsubTask.setDescription("Sub sub task");
                subsubTask.doFirst(action);
                subTask.dependsOn(subsubTask);

                taskContainer.registerTask(subTask);
                taskContainer.registerTask(subsubTask);
            }

            current = newTask;
        }

        new TaskExecutor().run(taskContainer, current);
    }

    @Test
    public void simulateBuildTasks() {
        TaskContainer container = new TaskContainer();

        Action<Task> action = t -> {
            System.out.println(t.getDescription());
        };

        DefaultTask aapt2Task = new DefaultTask();
        aapt2Task.setDescription("AAPT2");
        aapt2Task.doFirst(action);
        container.registerTask(aapt2Task);

        DefaultTask javaTask = new DefaultTask();
        javaTask.setDescription("JAVA");
        javaTask.doFirst(action);
        javaTask.dependsOn(aapt2Task);
        container.registerTask(javaTask);

        DefaultTask d8Task = new DefaultTask();
        d8Task.setDescription("D8");
        d8Task.doFirst(action);
        d8Task.dependsOn(javaTask);
        container.registerTask(d8Task);

        DefaultTask packageTask = new DefaultTask();
        packageTask.setDescription("Package");
        packageTask.doFirst(action);
        packageTask.dependsOn(d8Task);
        container.registerTask(packageTask);

        DefaultTask afterD8Task = new DefaultTask();
        afterD8Task.setDescription("After D8");
        afterD8Task.doFirst(action);
        d8Task.doLast(task -> afterD8Task.getActions().forEach(a -> a.execute(afterD8Task)));
        container.registerTask(afterD8Task);

        new TaskExecutor().run(container, packageTask);
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
        taskExecutor.run(task);

        System.out.println(taskRan);
    }


}
