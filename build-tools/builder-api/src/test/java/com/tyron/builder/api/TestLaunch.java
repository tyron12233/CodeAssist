package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.classpath.ClassPath;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.invocation.BuildAction;
import com.tyron.builder.api.internal.logging.events.CategorisedOutputEvent;
import com.tyron.builder.api.internal.logging.events.OutputEvent;
import com.tyron.builder.api.internal.logging.events.OutputEventListener;
import com.tyron.builder.api.internal.logging.events.RenderableOutputEvent;
import com.tyron.builder.api.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.api.internal.operations.BuildOperation;
import com.tyron.builder.api.internal.operations.BuildOperationContext;
import com.tyron.builder.api.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.api.internal.operations.BuildOperationQueue;
import com.tyron.builder.api.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.api.internal.operations.OperationIdentifier;
import com.tyron.builder.api.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.project.ProjectBuilderImpl;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.work.WorkerLeaseRegistry;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.configuration.GradleLauncherMetaData;
import com.tyron.builder.initialization.BuildClientMetaData;
import com.tyron.builder.initialization.BuildEventConsumer;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildRequestContext;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.internal.BuildType;
import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.buildTree.BuildActionModelRequirements;
import com.tyron.builder.internal.buildTree.BuildActionRunner;
import com.tyron.builder.internal.buildTree.BuildModelParameters;
import com.tyron.builder.internal.buildTree.BuildTreeActionExecutor;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildTree.BuildTreeState;
import com.tyron.builder.internal.buildTree.RunTasksRequirements;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.events.LogEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.sink.OutputEventListenerManager;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionContext;
import com.tyron.builder.internal.session.BuildSessionState;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;
import com.tyron.builder.launcher.ProjectLauncher;
import com.tyron.builder.launcher.exec.BuildTreeLifecycleBuildActionExecutor;
import com.tyron.common.TestUtil;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.function.Function;

public class TestLaunch {

    @Test
    public void testProjectBuilder() {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        File gradleUserHomeDir = new File(resourcesDirectory, ".gradle");
        File testProjectDir = new File(resourcesDirectory, "TestProject");

        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setGradleUserHomeDir(gradleUserHomeDir);
        startParameter.setProjectDir(testProjectDir);
        startParameter.setTaskNames(ImmutableList.of("testTask"));

        ProjectLauncher projectLauncher = new ProjectLauncher(startParameter) {
            @Override
            public void configure(BuildProject project) {
                project.getTasks().register("testTask", new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        task.getLogger().info("Configuring");

                        task.doLast(new Action<Task>() {
                            @Override
                            public void execute(Task task) {
                                task.getLogger().info("Executing");
                            }
                        });
                    }
                });
            }
        };
        projectLauncher.execute();
    }
}
