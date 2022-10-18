package org.gradle.tooling.internal.provider;

import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildActionResult;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.tooling.internal.provider.connection.ProviderOperationParameters;

public class DaemonBuildActionExecuter implements BuildActionExecuter<ConnectionOperationParameters, BuildRequestContext> {
    private final BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer;

    public DaemonBuildActionExecuter(BuildActionExecuter<BuildActionParameters, BuildRequestContext> executer) {
        this.executer = executer;
    }

    @Override
    public BuildActionResult execute(BuildAction action, ConnectionOperationParameters parameters, BuildRequestContext buildRequestContext) {
        ProviderOperationParameters operationParameters = parameters.getOperationParameters();
        ClassPath classPath = DefaultClassPath.of(operationParameters.getInjectedPluginClasspath());

        DaemonParameters daemonParameters = parameters.getDaemonParameters();
        BuildActionParameters actionParameters = new DefaultBuildActionParameters(daemonParameters.getEffectiveSystemProperties(), daemonParameters.getEnvironmentVariables(), SystemProperties.getInstance().getCurrentDir(), operationParameters.getBuildLogLevel(), daemonParameters.isEnabled(), classPath);
        return executer.execute(action, actionParameters, buildRequestContext);
    }
}