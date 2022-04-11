package com.tyron.builder.api;

import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.Describables;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.logging.services.DefaultStyledTextOutputFactory;
import com.tyron.builder.api.internal.operations.MultipleBuildOperationFailures;
import com.tyron.builder.api.internal.project.DefaultProjectOwner;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServiceRegistryFactory;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GlobalServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GradleUserHomeScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.util.Path;
import com.tyron.builder.internal.buildevents.BuildExceptionReporter;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.common.TestUtil;

import org.apache.commons.io.monitor.FileAlterationListener;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.impl.SimpleLogger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public abstract class TestTaskExecutionCase {

    private final ResourceLock lock = new DefaultLock();
    protected ProjectInternal project;

    private DefaultTaskContainer container;

    @Before
    public void setup() throws Exception {
        System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "Debug");
        StartParameterInternal startParameter = new StartParameterInternal() {
            @Override
            public boolean isRerunTasks() {
                return false;
            }
        };


        DefaultServiceRegistry global = new GlobalServices();
        global.register(this::registerGlobalServices);

        GradleUserHomeScopeServices gradleUserHomeScopeServices = new GradleUserHomeScopeServices(global);
        BuildScopeServices buildScopeServices = new BuildScopeServices(gradleUserHomeScopeServices);

        BuildScopeServiceRegistryFactory registryFactory = new BuildScopeServiceRegistryFactory(buildScopeServices);

        DefaultGradle gradle = new DefaultGradle(null, startParameter, registryFactory) {

            private ServiceRegistry registry;
            private ServiceRegistryFactory factory;
            private TaskExecutionGraphInternal taskExecutionGraph;

            @Override
            public File getGradleUserHomeDir() {
                return new File(TestUtil.getResourcesDirectory(), ".gradle");
            }

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

        String projectName = getProjectName();

        File resourcesDir = TestUtil.getResourcesDirectory();
        File testProjectDir = new File(resourcesDir, projectName);

        ProjectFactory projectFactory = gradle.getServices().get(ProjectFactory.class);
        DefaultProjectOwner owner = DefaultProjectOwner.builder()
                .setProjectDir(testProjectDir)
                .setProjectPath(Path.ROOT)
                .setIdentityPath(Path.ROOT)
                .setDisplayName(Describables.of(projectName))
                .setTaskExecutionLock(lock).setAccessLock(lock).build();
        project = projectFactory
                .createProject(gradle, new DefaultProjectDescriptor(projectName), owner, null);
        project.setBuildDir(new File(testProjectDir, "build"));

        container = (DefaultTaskContainer) this.project.getTasks(); FileAlterationObserver observer = new FileAlterationObserver(project.getRootDir());
        FileAlterationMonitor monitor = new FileAlterationMonitor(1);
        FileAlterationListener listener = new FileAlterationListenerAdaptor() {

            private final VirtualFileSystem vfs = project.getServices().get(VirtualFileSystem.class);

            @Override
            public void onFileDelete(File file) {
                vfs.invalidate(Collections.singletonList(file.getAbsolutePath()));
            }

            @Override
            public void onDirectoryChange(File directory) {
                vfs.invalidate(Collections.singletonList(directory.getAbsolutePath()));
            }
        };

        observer.addListener(listener);
        monitor.addObserver(observer);
        monitor.start();
    }

    protected String getProjectName() {
        return "TestProject";
    }

    protected void registerGlobalServices(ServiceRegistration serviceRegistration) {

    }

    @Test
    public void test() {
        evaluateProject(project, this::evaluateProject);

        if (project.getState().hasFailure()) {
            project.getState().rethrowFailure();
        }

        executeProject(project, getTasksToExecute().toArray(new String[0]));
    }

    public abstract void evaluateProject(BuildProject project);

    public abstract List<String> getTasksToExecute();

    /**
     * Used to evaluate the project and mutate its state
     * @param project the project to evaluate
     * @param evaluationAction the action to run that will evaluate the project
     */
    private void evaluateProject(ProjectInternal project, Action<BuildProject> evaluationAction) {
        project.getState().toBeforeEvaluate();
        project.getState().toEvaluate();
        try {
            evaluationAction.execute(project);
        } catch (Throwable e) {
            project.getState()
                    .failed(new ProjectConfigurationException("Failed to evaluate project.", e));
        }

        project.getState().toAfterEvaluate();

        if (!project.getState().hasFailure()) {
            project.getState().configured();
        }
    }

    /**
     * Runs the given tasks on the project
     * @param project The project
     * @param taskNames The names of the tasks to run
     */
    private void executeProject(ProjectInternal project, String... taskNames) {
        TaskExecutor taskExecutor = new TaskExecutor(project);
        taskExecutor.execute(taskNames);
        List<Throwable> failures = taskExecutor.getFailures();

        if (failures.isEmpty()) {
            return;
        }

        throwFailures(failures);

        throw new AssertionError(failures);
    }

    private void throwFailures(List<Throwable> throwables) {
        BuildExceptionReporter buildExceptionReporter =
                new BuildExceptionReporter(new DefaultStyledTextOutputFactory(event -> {

                }, Time.clock()), new LoggingConfiguration() {
                    @Override
                    public LogLevel getLogLevel() {
                        return LogLevel.DEBUG;
                    }

                    @Override
                    public void setLogLevel(LogLevel logLevel) {

                    }

                    @Override
                    public ConsoleOutput getConsoleOutput() {
                        return ConsoleOutput.Rich;
                    }

                    @Override
                    public void setConsoleOutput(ConsoleOutput consoleOutput) {

                    }

                    @Override
                    public WarningMode getWarningMode() {
                        return WarningMode.All;
                    }

                    @Override
                    public void setWarningMode(WarningMode warningMode) {

                    }

                    @Override
                    public ShowStacktrace getShowStacktrace() {
                        return ShowStacktrace.ALWAYS_FULL;
                    }

                    @Override
                    public void setShowStacktrace(ShowStacktrace showStacktrace) {

                    }
                }, (output, args) -> {

                });
        buildExceptionReporter.buildFinished(new BuildResult(
                project.getGradle(),
                new MultipleBuildOperationFailures(throwables, null)
        ));
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
