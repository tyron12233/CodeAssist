package com.tyron.builder.api;

import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.plan.DefaultExecutionPlan;
import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.DependencyResolver;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchy;
import com.tyron.builder.api.execution.plan.ExecutionPlan;
import com.tyron.builder.api.execution.plan.Node;
import com.tyron.builder.api.execution.plan.NodeExecutor;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.internal.execution.DefaultTaskExecutionGraph;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.project.AbstractProject;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.tasks.CircularDependencyException;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.tasks.WorkDependencyResolver;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * WIP
 */
public class TestTaskExecution {

    private DefaultTaskContainer container;

    @Before
    public void setup() {
        AbstractProject project = new AbstractProject() {
        };
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

        TaskNodeFactory taskNodeFactory = new TaskNodeFactory(new DefaultNodeValidator());
        TaskDependencyResolver resolver = new TaskDependencyResolver(Collections.singletonList(new TaskNodeDependencyResolver(taskNodeFactory)));
        ExecutionNodeAccessHierarchy executionNodeAccessHierarchy = new ExecutionNodeAccessHierarchy(
                CaseSensitivity.CASE_INSENSITIVE, new Stat() {
            @Override
            public int getUnixMode(File f) throws FileException {
                return 0;
            }

            @Override
            public FileMetadata stat(File f) throws FileException {
                return new FileMetadata() {
                    @Override
                    public FileType getType() {
                        if (!f.exists()) {
                            return FileType.Missing;
                        }
                        if (f.isDirectory()) {
                            return FileType.Directory;
                        }
                        return FileType.RegularFile;
                    }

                    @Override
                    public long getLastModified() {
                        return f.lastModified();
                    }

                    @Override
                    public long getLength() {
                        return f.length();
                    }

                    @Override
                    public AccessType getAccessType() {
                        if (f.isAbsolute()) {
                            return AccessType.DIRECT;
                        }
                        return AccessType.VIA_SYMLINK;
                    }
                };
            }
        });
        DefaultExecutionPlan executionPlan = new DefaultExecutionPlan("myPlan", taskNodeFactory, resolver, executionNodeAccessHierarchy,
                                                                      executionNodeAccessHierarchy);
        executionPlan.addEntryTasks(Collections.singletonList(container.resolveTask("task3")));
        executionPlan.determineExecutionPlan();

        DefaultTaskExecutionGraph graph = new DefaultTaskExecutionGraph(new PlanExecutor() {
            @Override
            public void process(ExecutionPlan executionPlan,
                                Collection<? super Throwable> failures,
                                Action<Node> nodeExecutor) {

            }

            @Override
            public void assertHealthy() {

            }
        }, Collections.singletonList((node, context) -> false));
        graph.populate(executionPlan);

        List<Throwable> failures = new ArrayList<>();
        graph.execute(executionPlan, failures);

        assert failures.isEmpty();
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
