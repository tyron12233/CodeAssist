package org.gradle.launcher;

import org.gradle.StartParameter;
import org.gradle.api.Action;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.Factory;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.internal.vfs.VirtualFileSystem;
import org.gradle.launcher.cli.ExceptionReportingAction;
import org.gradle.launcher.cli.RunBuildAction;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildExecuter;
import org.gradle.launcher.exec.DefaultBuildActionParameters;

import java.util.Collections;

public class ProjectLauncher {

    private final StartParameterInternal startParameter;
    private final ServiceRegistry globalServices;
    private final OutputEventListener outputEventListener;

    public ProjectLauncher(StartParameterInternal startParameter) {
        this(startParameter, null);
    }

    public ProjectLauncher(StartParameterInternal startParameter, OutputEventListener outputEventListener) {
        this.startParameter = startParameter;
        this.outputEventListener = outputEventListener;
        globalServices = ProjectBuilderImpl.getGlobalServices(startParameter);
    }

    public ServiceRegistry getGlobalServices() {
        return globalServices;
    }

    private void prepare() {

    }

    public void execute() {
        Runnable runnable = runBuildAndCloseServices(
                startParameter,
                globalServices.get(BuildExecuter.class),
                globalServices,
                globalServices.get(GradleUserHomeScopeServiceRegistry.class)
        );
        Action<Throwable> reporter = throwable -> {

        };
        LoggingManagerInternal loggingManagerInternal = globalServices.get(LoggingManagerInternal.class);

        ExceptionReportingAction action = new ExceptionReportingAction(reporter,
                loggingManagerInternal, executionListener -> {
            runnable.run();
        });

        prepare();
        action.execute(failure -> {

        });
    }

    private Runnable runBuildAndCloseServices(StartParameterInternal startParameter, BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer, ServiceRegistry sharedServices, Object... stopBeforeSharedServices) {
        BuildActionParameters
                parameters = createBuildActionParameters(startParameter);
        return new RunBuildAction(executer, startParameter, clientMetaData(), getBuildStartTime(), parameters, sharedServices, null);
    }

    private BuildActionParameters createBuildActionParameters(StartParameter startParameter) {
        return new DefaultBuildActionParameters(
//                daemonParameters.getEffectiveSystemProperties(),
                Collections.singletonMap("ANDROID_HOME", startParameter.getGradleUserHomeDir()),
//                daemonParameters.getEnvironmentVariables(),
                Collections.emptyMap(),
                SystemProperties.getInstance().getCurrentDir(),
                startParameter.getLogLevel(),
//                daemonParameters.isEnabled(),
                false,
                ClassPath.EMPTY);
    }

    private long getBuildStartTime() {
        return System.currentTimeMillis();
    }

    private GradleLauncherMetaData clientMetaData() {
        return new GradleLauncherMetaData();
    }
}
