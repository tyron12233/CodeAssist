package com.tyron.builder.api;

import com.tyron.builder.api.execution.plan.DefaultExecutionPlan;
import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchy;
import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.execution.plan.Node;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.project.AbstractProject;
import com.tyron.builder.api.internal.resources.DefaultLease;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * WIP
 */
public class TestTaskExecution {

    AbstractProject project = new AbstractProject() {
    };
    private DefaultTaskContainer container;

    @Before
    public void setup() {
        container = (DefaultTaskContainer) project.getTaskContainer();
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

        container.register("injectedTask", task -> {
            task.doLast(executedTasks::add);
            task.mustRunAfter("task1");
        });

        TaskExecutor executor = new TaskExecutor(project);
        executor.execute("task3", "injectedTask");

        assertExecutionOrder(executedTasks, "task2", "task1", "task3", "injectedTask");
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
            TaskExecutor taskExecutor = new TaskExecutor(project);
            taskExecutor.execute("task1");
        } catch (CircularDependencyException expected) {
            return;
        }

        throw new AssertionError("Circular dependency was not detected.");
    }

    private void assertExecutionOrder(List<Task> tasks, String... executionOrder) {
        if (tasks.size() != executionOrder.length) {
            throw new AssertionError(
                    "Expected " + executionOrder.length + " but got " + tasks.size());
        }

        for (int i = 0; i < executionOrder.length; i++) {
            Task task = tasks.get(i);
            String name = executionOrder[i];

            if (!task.getName().equals(name)) {
                throw new AssertionError("Execution order not met. Tasks ran: " +
                                         tasks +
                                         "\n" +
                                         "Expected order: " +
                                         Arrays.toString(executionOrder));
            }
        }
    }

}
