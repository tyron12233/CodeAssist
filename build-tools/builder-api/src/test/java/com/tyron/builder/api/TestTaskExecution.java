package com.tyron.builder.api;

import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.Describables;
import com.tyron.builder.api.internal.MutableBoolean;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.execution.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.logging.services.DefaultStyledTextOutputFactory;
import com.tyron.builder.api.internal.operations.MultipleBuildOperationFailures;
import com.tyron.builder.api.internal.project.DefaultProjectOwner;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServiceRegistryFactory;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GlobalServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GradleUserHomeScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.util.Path;
import com.tyron.builder.internal.buildevents.BuildExceptionReporter;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public class TestTaskExecution extends TestTaskExecutionCase {

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
        Action<BuildProject> evaluationAction = new Action<BuildProject>() {
            @Override
            public void execute(BuildProject project) {
                TaskContainer tasks = project.getTasks();
                tasks.register("SkipTask", new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        task.onlyIf(new Predicate<Task>() {
                            @Override
                            public boolean test(Task t) {
                                return false;
                            }
                        });
                        task.doLast(new Action<Task>() {
                            @Override
                            public void execute(Task t) {
                                executed.set(true);
                            }
                        });
                    }
                });
            }
        };

        evaluateProject(project, evaluationAction);
        executeProject(project, "SkipTask");

        assert !executed.get();
    }

}
