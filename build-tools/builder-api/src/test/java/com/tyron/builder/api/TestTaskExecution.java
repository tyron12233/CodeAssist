package com.tyron.builder.api;

import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.Describables;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.project.DefaultProjectOwner;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistryBuilder;
import com.tyron.builder.api.internal.reflect.service.scopes.ExecutionGlobalServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GradleScopeServices;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServiceRegistryFactory;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.util.Path;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WIP
 */
public class TestTaskExecution {

    private final ResourceLock lock = new DefaultLock();
    ProjectInternal project;

    private DefaultTaskContainer container;

    @Before
    public void setup() throws IOException {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        StartParameterInternal startParameter = new StartParameterInternal() {};

        DefaultServiceRegistry global = new DefaultServiceRegistry();
        global.register(registration -> {
            registration.add(PropertyWalker.class, (instance, validationContext, visitor) -> {

            });
        });
        BuildScopeServices buildScopeServices = new BuildScopeServices(global);
        BuildScopeServiceRegistryFactory registryFactory =
                new BuildScopeServiceRegistryFactory(buildScopeServices);

        DefaultGradle gradle = new DefaultGradle(null, startParameter, registryFactory) {

            private ServiceRegistry registry;
            private ServiceRegistryFactory factory;
            private TaskExecutionGraphInternal taskExecutionGraph;

            @Override
            public TaskExecutionGraphInternal getTaskGraph() {
                if (taskExecutionGraph == null) {
                    taskExecutionGraph = getServices().get(TaskExecutionGraphInternal.class);
                }
                return taskExecutionGraph;
            }

            @Override
            public ServiceRegistry getServices() {
                if (registry == null) {
                    registry = registryFactory.createFor(this);
                }
                return registry;
            }

            @Override
            public ServiceRegistryFactory getServiceRegistryFactory() {
                if (factory == null) {
                    factory = getServices().get(ServiceRegistryFactory.class);
                }
                return factory;
            }
        };

        global.add(gradle);

        ProjectFactory projectFactory = gradle.getServices().get(ProjectFactory.class);
        DefaultProjectOwner owner = DefaultProjectOwner.builder()
                .setProjectDir(resourcesDirectory)
                .setProjectPath(Path.ROOT)
                .setDisplayName(Describables.of("TestProject"))
                .setTaskExecutionLock(lock)
                .setAccessLock(lock)
                .build();
        project = projectFactory.createProject(
                gradle,
                new DefaultProjectDescriptor("TestProject"),
                owner,
                null
        );
        container = (DefaultTaskContainer) this.project.getTasks();
    }

    @Test
    public void testProjectCreation() {
        assert project != null;
    }

    @Test
    public void testProject() {
        Action<BuildProject> evaluationAction = project -> {
            TaskContainer tasks = project.getTasks();
            tasks.register("MyTask", task -> {
                task.doLast(__ -> {
                    System.out.println("Running " + task.getName());
                });
            });
        };
        evaluateProject(project, evaluationAction);

        executeProject(project, "MyTask");
    }

    private void evaluateProject(ProjectInternal project, Action<BuildProject> evaluationAction) {
        project.getState().toBeforeEvaluate();
        project.getState().toEvaluate();
        try {
            evaluationAction.execute(project);
        } catch (Throwable e) {
            project.getState().failed(new ProjectConfigurationException("Failed to evaluate project.", e));
        }

        project.getState().toAfterEvaluate();

        if (!project.getState().hasFailure()) {
            project.getState().configured();
        }
    }

    private void executeProject(ProjectInternal project, String... taskNames) {
        TaskExecutor taskExecutor = new TaskExecutor(project);
        taskExecutor.execute(taskNames);
    }

//
//    private void registerServices(ServiceRegistry services, ServiceRegistration registration, Object domainObject) {
//        if (domainObject instanceof ProjectInternal) {
//            registration.addProvider(new ProjectScopeServices(services,
//                                                              ((ProjectInternal) domainObject)));
//        }
//    }
//
//    @Test
//    public void testTaskDependency() {
//        List<Task> executedTasks = new ArrayList<>();
//
//        container.register("task1", task -> {
//            task.doLast(executedTasks::add);
//            task.dependsOn("task2");
//        });
//
//        container.register("task2", task -> {
//            task.doLast(executedTasks::add);
//        });
//
//        container.register("task3", task -> {
//            task.doLast(executedTasks::add);
//            task.dependsOn("task1");
//        });
//
//        container.register("injectedTask", task -> {
//            task.doLast(executedTasks::add);
//            task.mustRunAfter("task1");
//        });
//
//        TaskExecutor executor = new TaskExecutor((ProjectInternal) project);
//        executor.execute("task3", "injectedTask");
//
//        assertExecutionOrder(executedTasks, "task2", "task1", "task3", "injectedTask");
//    }
//
//    @Test
//    public void testNoCircularDependency() {
//        container.register("task1", task -> {
//            task.dependsOn("task2");
//        });
//        container.register("task2", task -> {
//            task.dependsOn("task1");
//        });
//        try {
//            TaskExecutor taskExecutor = new TaskExecutor((ProjectInternal) project);
//            taskExecutor.execute("task1");
//        } catch (CircularDependencyException expected) {
//            return;
//        }
//
//        throw new AssertionError("Circular dependency was not detected.");
//    }
//
//    private void assertExecutionOrder(List<Task> tasks, String... executionOrder) {
//        if (tasks.size() != executionOrder.length) {
//            throw new AssertionError(
//                    "Expected " + executionOrder.length + " but got " + tasks.size());
//        }
//
//        for (int i = 0; i < executionOrder.length; i++) {
//            Task task = tasks.get(i);
//            String name = executionOrder[i];
//
//            if (!task.getName().equals(name)) {
//                throw new AssertionError("Execution order not met. Tasks ran: " +
//                                         tasks +
//                                         "\n" +
//                                         "Expected order: " +
//                                         Arrays.toString(executionOrder));
//            }
//        }
//    }
//
//    @Test
//    public void mockAssembleTasks() {
//
//        container.register("AAPT2", task -> {
//            task.doLast(it -> {
//                System.out.println("> Task " + task.getPath());
//            });
//        });
//        container.register("JAVA", task -> {
//            task.doLast(it -> {
//                System.out.println("> Task " + task.getPath());
//            });
//            task.dependsOn("AAPT2");
//        });
//        container.register("D8", task -> {
//            task.doLast(it -> {
//                System.out.println("> Task " + task.getPath());
//            });
//            task.dependsOn("JAVA");
//        });
//        container.register("PACKAGE", task -> {
//            task.doLast(it -> {
//                System.out.println("> Task " + task.getPath());
//            });
//            task.dependsOn("D8");
//        });
//
//        container.register("assemble", task -> {
//            task.doLast(it -> {
//                System.out.println("> Task " + task.getPath());
//            });
//            task.dependsOn("PACKAGE");
//        });
//
//        new TaskExecutor(project).execute("assemble");
//    }
//
    private static class DefaultLock implements ResourceLock {

        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public boolean isLocked() {
            return lock.isLocked();
        }

        @Override
        public boolean isLockedByCurrentThread() {
            return lock.isHeldByCurrentThread();
        }

        @Override
        public boolean tryLock() {
            return lock.tryLock();
        }

        @Override
        public void unlock() {
            lock.unlock();
        }

        @Override
        public String getDisplayName() {
            return lock.toString();
        }
    }
}
