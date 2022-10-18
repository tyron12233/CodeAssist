package com.tyron.builder.initialization;

import com.google.common.collect.ImmutableList;

import org.gradle.StartParameter;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.initialization.*;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.installation.CurrentGradleInstallation;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.services.LoggingServiceRegistry;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.BasicGlobalScopeServices;
import org.gradle.internal.service.scopes.GlobalServices;
import org.gradle.internal.time.Time;
import org.gradle.launcher.cli.converter.BuildLayoutConverter;
import org.gradle.launcher.daemon.client.DaemonClient;
import org.gradle.launcher.daemon.client.DaemonClientFactory;
import org.gradle.launcher.daemon.client.DaemonClientGlobalServices;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.tooling.internal.provider.action.ExecuteBuildAction;

import java.io.File;

public class Launcher {

    public static void main(String[] args) {
        Launcher launcher = new Launcher();
        launcher.launch();
    }

    ServiceRegistry basicServices;

    public Launcher() {
        NativeServices.initializeOnClient(CurrentGradleInstallation.get().getGradleHome());
        LoggingServiceRegistry loggingServiceRegistry =
                LoggingServiceRegistry.newCommandLineProcessLogging();
        basicServices = ServiceRegistryBuilder.builder()
                .parent(loggingServiceRegistry)
                .parent(NativeServices.getInstance())
                .provider(new BasicGlobalScopeServices()).build();
    }

    public void launch() {
        ServiceRegistry clientSharedServices = createGlobalClientServices(true);
        DaemonParameters daemonParameters = new DaemonParameters(
                new BuildLayoutConverter().defaultValues(),
                clientSharedServices.get(FileCollectionFactory.class)
        );


        ServiceRegistry singleUseDaemonClientServices = clientSharedServices.get(DaemonClientFactory.class)
                        .createSingleUseDaemonClientServices(event -> System.out.println(event.toString()), daemonParameters, System.in);
        DaemonClient daemonClient = singleUseDaemonClientServices.get(DaemonClient.class);

        StartParameter startParameter = createStartParameter();
        BuildActionParameters parameters = createBuildActionParameters(startParameter);
        BuildRequestContext buildRequestContext = createBuildRequestContext();
        BuildAction buildAction = new ExecuteBuildAction((StartParameterInternal) startParameter);

        daemonClient.execute(buildAction, parameters, buildRequestContext);
    }

    private StartParameter createStartParameter() {
        File projectDir = new File("C:\\Users\\tyron scott\\StudioProjects\\CodeAssist\\build-tools\\builder-launcher\\src\\test\\resources\\TestProject");
        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setLogLevel(LogLevel.DEBUG);
        startParameter.setShowStacktrace(ShowStacktrace.ALWAYS_FULL);
        startParameter.setProjectDir(projectDir);
        startParameter.setBuildCacheEnabled(true);
        startParameter.setCurrentDir(projectDir);
        startParameter.setTaskNames(ImmutableList.of(":consumer:assemble"));
        return startParameter;
    }

    private BuildActionParameters createBuildActionParameters(StartParameter startParameter) {
        return new DefaultBuildActionParameters(
                System.getProperties(),
                System.getenv(),
                SystemProperties.getInstance().getCurrentDir(),
                startParameter.getLogLevel(),
                false,
                ClassPath.EMPTY
        );
    }

    private BuildRequestContext createBuildRequestContext() {
        return new DefaultBuildRequestContext(
                new DefaultBuildRequestMetaData(new GradleLauncherMetaData(), Time.currentTimeMillis(), false),
                new DefaultBuildCancellationToken(),
                new NoOpBuildEventConsumer());
    }

    private ServiceRegistry createGlobalClientServices(boolean usingDaemon) {
        ServiceRegistryBuilder builder = ServiceRegistryBuilder.builder()
                .displayName("Daemon client global services")
                .parent(NativeServices.getInstance());
        if (usingDaemon) {
            builder.parent(basicServices);
        } else {
            builder.provider(new GlobalServices(true));
        }
        return builder.provider(new DaemonClientGlobalServices()).build();
    }
}
