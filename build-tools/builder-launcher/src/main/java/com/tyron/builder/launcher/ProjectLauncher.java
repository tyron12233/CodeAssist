package com.tyron.builder.launcher;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.configuration.GradleLauncherMetaData;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.initialization.ReportedException;
import com.tyron.builder.internal.SystemProperties;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.service.scopes.VirtualFileSystemServices;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.launcher.bootstrap.ExecutionListener;
import com.tyron.builder.launcher.cli.ExceptionReportingAction;
import com.tyron.builder.launcher.cli.RunBuildAction;
import com.tyron.builder.launcher.exec.BuildActionExecuter;
import com.tyron.builder.launcher.exec.BuildActionParameters;
import com.tyron.builder.launcher.exec.BuildExecuter;
import com.tyron.builder.launcher.exec.DefaultBuildActionParameters;

import java.util.Collections;
import java.util.List;

public abstract class ProjectLauncher {

    private final StartParameterInternal startParameter;
    private final ServiceRegistry globalServices;

    public ProjectLauncher(StartParameterInternal startParameter) {
        this(startParameter, Collections.emptyList());
    }

    public ProjectLauncher(StartParameterInternal startParameter,
                           List<PluginServiceRegistry> pluginServiceRegistries) {
        this.startParameter = startParameter;
        globalServices = ProjectBuilderImpl.getGlobalServices(startParameter);
    }

    public ServiceRegistry getGlobalServices() {
        return globalServices;
    }

    private void prepare() {
        GradleUserHomeScopeServiceRegistry gradleUserHomeScopeServiceRegistry =
                globalServices.get(GradleUserHomeScopeServiceRegistry.class);
        ServiceRegistry gradleUserHomeScopeServices = gradleUserHomeScopeServiceRegistry
                .getServicesFor(startParameter.getGradleUserHomeDir());
        VirtualFileSystem virtualFileSystem =
                gradleUserHomeScopeServices.get(VirtualFileSystem.class);

        FileSystemAccess fileSystemAccess = gradleUserHomeScopeServices.get(FileSystemAccess.class);
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
        LoggingManagerInternal loggingManagerInternal =
                globalServices.get(LoggingManagerInternal.class);
        ExceptionReportingAction action = new ExceptionReportingAction(reporter,
                loggingManagerInternal, executionListener -> runnable.run());

        prepare();

        action.execute(failure -> {
            throw UncheckedException.throwAsUncheckedException(failure);
        });


    }

    private Runnable runBuildAndCloseServices(StartParameterInternal startParameter, BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer, ServiceRegistry sharedServices, Object... stopBeforeSharedServices) {
        BuildActionParameters
                parameters = createBuildActionParameters(startParameter);
        Stoppable stoppable = new CompositeStoppable(); //.add(stopBeforeSharedServices).add(sharedServices);
        return new RunBuildAction(executer, startParameter, clientMetaData(), getBuildStartTime(), parameters, sharedServices, stoppable);
    }

    private BuildActionParameters createBuildActionParameters(StartParameter startParameter) {
        return new DefaultBuildActionParameters(
//                daemonParameters.getEffectiveSystemProperties(),
                Collections.emptyMap(),
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

    /**
     * Callback to configure the given project. This will be called on the root project and
     * any existing projects that will be registered.
     *
     * @param project The project to configure
     */
    public abstract void configure(BuildProject project);
}
