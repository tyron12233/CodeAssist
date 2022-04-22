package com.tyron.builder.launcher;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.configuration.GradleLauncherMetaData;
import com.tyron.builder.initialization.BuildRequestContext;
import com.tyron.builder.initialization.ReportedException;
import com.tyron.builder.internal.SystemProperties;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
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

    }

    public void execute() {
        prepare();

        Runnable runnable = runBuildAndCloseServices(
                startParameter,
                globalServices.get(BuildExecuter.class),
                globalServices,
                globalServices.get(GradleUserHomeScopeServiceRegistry.class)
        );
        Action<Throwable> reporter = throwable -> {

        };
        Action<ExecutionListener> executionListenerAction = executionListener -> {

        };
        LoggingManagerInternal loggingManagerInternal =
                globalServices.get(LoggingManagerInternal.class);
        ExceptionReportingAction action = new ExceptionReportingAction(reporter,
                loggingManagerInternal, new Action<ExecutionListener>() {
            @Override
            public void execute(ExecutionListener executionListener) {
                runnable.run();
            }
        });
        action.execute(failure -> {
            throw new ReportedException();
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
