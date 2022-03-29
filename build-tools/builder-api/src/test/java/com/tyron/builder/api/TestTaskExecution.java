package com.tyron.builder.api;

import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.Describables;
import com.tyron.builder.api.internal.MutableBoolean;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.project.DefaultProjectOwner;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServiceRegistryFactory;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.util.Path;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
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

    @Test
    public void testSkipOnlyIf() {
        MutableBoolean executed = new MutableBoolean(false);
        Action<BuildProject> evaluationAction = project -> {
            TaskContainer tasks = project.getTasks();
            tasks.register("SkipTask", task -> {
                task.onlyIf(t -> false);
                task.doLast(__ -> executed.set(true));
            });
        };

        evaluateProject(project, evaluationAction);
        executeProject(project, "SkipTask");

        assert !executed.get();
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
        List<Throwable> failures = taskExecutor.getFailures();
        assert  failures.isEmpty() : "Project execution failure: " + failures;
    }

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
