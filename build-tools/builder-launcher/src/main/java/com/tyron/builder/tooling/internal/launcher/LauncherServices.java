package com.tyron.builder.tooling.internal.launcher;

import com.tyron.builder.api.internal.changedetection.state.FileHasherStatistics;
import com.tyron.builder.execution.WorkValidationWarningReporter;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.initialization.BuildEventConsumer;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.buildevents.BuildStartedTime;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeActionExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.classpath.CachedClasspathTransformer;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.file.StatStatistics;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationListenerManager;
import com.tyron.builder.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.internal.operations.BuildOperationRunner;
import com.tyron.builder.internal.operations.logging.LoggingBuildOperationProgressBroadcaster;
import com.tyron.builder.internal.operations.notify.BuildOperationNotificationValve;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionActionExecutor;
import com.tyron.builder.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.launcher.exec.BuildCompletionNotifyingBuildActionRunner;
import com.tyron.builder.launcher.exec.BuildExecuter;
import com.tyron.builder.launcher.exec.BuildTreeLifecycleBuildActionExecutor;
import com.tyron.builder.launcher.exec.ChainingBuildActionRunner;
import com.tyron.builder.launcher.exec.RootBuildLifecycleBuildActionExecutor;
import com.tyron.builder.launcher.exec.RunAsBuildOperationBuildActionExecutor;
import com.tyron.builder.launcher.exec.RunAsWorkerThreadBuildActionExecutor;
import com.tyron.builder.tooling.internal.provider.BuildOutcomeReportingBuildActionRunner;
import com.tyron.builder.tooling.internal.provider.BuildSessionLifecycleBuildActionExecuter;
import com.tyron.builder.tooling.internal.provider.ExecuteBuildActionRunner;
import com.tyron.builder.tooling.internal.provider.FileSystemWatchingBuildActionRunner;
import com.tyron.builder.tooling.internal.provider.SessionFailureReportingActionExecuter;
import com.tyron.builder.tooling.internal.provider.SetupLoggingActionExecuter;
import com.tyron.builder.tooling.internal.provider.StartParamsValidatingActionExecuter;
import com.tyron.builder.tooling.internal.provider.serialization.ClassLoaderCache;
import com.tyron.builder.tooling.internal.provider.serialization.DaemonSidePayloadClassLoaderFactory;
import com.tyron.builder.tooling.internal.provider.serialization.DefaultPayloadClassLoaderRegistry;
import com.tyron.builder.tooling.internal.provider.serialization.ModelClassLoaderFactory;
import com.tyron.builder.tooling.internal.provider.serialization.PayloadClassLoaderFactory;
import com.tyron.builder.tooling.internal.provider.serialization.PayloadSerializer;
import com.tyron.builder.tooling.internal.provider.serialization.WellKnownClassLoaderRegistry;

import java.util.List;

public class LauncherServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingGlobalScopeServices());
    }

    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingGradleUserHomeScopeServices());
    }

    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingBuildTreeScopeServices());
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingBuildSessionScopeServices());
    }

    static class ToolingGlobalScopeServices {

        BuildExecuter createBuildExecuter(StyledTextOutputFactory styledTextOutputFactory,
                                          LoggingManagerInternal loggingManager,
                                          WorkValidationWarningReporter workValidationWarningReporter,
                                          GradleUserHomeScopeServiceRegistry userHomeServiceRegistry,
                                          ServiceRegistry globalServices) {
            // @formatter:off
            return new SetupLoggingActionExecuter(loggingManager,
                    new SessionFailureReportingActionExecuter(styledTextOutputFactory, Time.clock(),
                            workValidationWarningReporter, new StartParamsValidatingActionExecuter(
                            new BuildSessionLifecycleBuildActionExecuter(userHomeServiceRegistry,
                                    globalServices))));
            // @formatter:on
        }

        ExecuteBuildActionRunner createExecuteBuildActionRunner() {
            return new ExecuteBuildActionRunner();
        }

        ClassLoaderCache createClassLoaderCache() {
            return new ClassLoaderCache();
        }
    }

    static class ToolingGradleUserHomeScopeServices {
        PayloadClassLoaderFactory createClassLoaderFactory(CachedClasspathTransformer cachedClasspathTransformer) {
            return new DaemonSidePayloadClassLoaderFactory(new ModelClassLoaderFactory(),
                    cachedClasspathTransformer);
        }

        PayloadSerializer createPayloadSerializer(ClassLoaderCache classLoaderCache,
                                                  PayloadClassLoaderFactory classLoaderFactory) {
            return new PayloadSerializer(new WellKnownClassLoaderRegistry(
                    new DefaultPayloadClassLoaderRegistry(classLoaderCache, classLoaderFactory)));
        }
    }

    static class ToolingBuildSessionScopeServices {

        BuildSessionActionExecutor createActionExecutor(
//                BuildEventListenerFactory listenerFactory,
                ExecutorFactory executorFactory,
                ListenerManager listenerManager,
                BuildOperationListenerManager buildOperationListenerManager,
                BuildOperationExecutor buildOperationExecutor,
//                TaskInputsListeners inputsListeners,
                StyledTextOutputFactory styledTextOutputFactory,
//                FileSystemChangeWaiterFactory fileSystemChangeWaiterFactory,
                BuildRequestMetaData requestMetaData,
                BuildCancellationToken cancellationToken,
//                DeploymentRegistryInternal deploymentRegistry,
                BuildEventConsumer eventConsumer,
                BuildStartedTime buildStartedTime,
                Clock clock,
                LoggingBuildOperationProgressBroadcaster loggingBuildOperationProgressBroadcaster,
                BuildOperationNotificationValve buildOperationNotificationValve,
                BuildTreeModelControllerServices buildModelServices,
                WorkerLeaseService workerLeaseService,
                BuildLayoutValidator buildLayoutValidator) {
            return new RunAsWorkerThreadBuildActionExecutor(workerLeaseService,
                    new RunAsBuildOperationBuildActionExecutor(
                            new BuildTreeLifecycleBuildActionExecutor(buildModelServices,
                                    buildLayoutValidator), buildOperationExecutor,
                            loggingBuildOperationProgressBroadcaster,
                            buildOperationNotificationValve));
        }
    }

    static class ToolingBuildTreeScopeServices {
        BuildTreeActionExecutor createActionExecutor(List<BuildActionRunner> buildActionRunners,
                                                     StyledTextOutputFactory styledTextOutputFactory,
                                                     BuildStateRegistry buildStateRegistry,
                                                     BuildOperationProgressEventEmitter eventEmitter,
                                                     WorkValidationWarningReporter workValidationWarningReporter,
                                                     ListenerManager listenerManager,
                                                     BuildStartedTime buildStartedTime,
                                                     BuildRequestMetaData buildRequestMetaData,
//                GradleEnterprisePluginManager gradleEnterprisePluginManager,
                                                     BuildLifecycleAwareVirtualFileSystem virtualFileSystem,
//                StatStatistics.Collector statStatisticsCollector,
//                FileHasherStatistics.Collector fileHasherStatisticsCollector,
                                                     DirectorySnapshotterStatistics.Collector directorySnapshotterStatisticsCollector,
                                                     BuildOperationRunner buildOperationRunner,
                                                     Clock clock
//                BuildLayout buildLayout,
//                ExceptionAnalyser exceptionAnalyser
        ) {
            return new RootBuildLifecycleBuildActionExecutor(buildStateRegistry,
                    new BuildCompletionNotifyingBuildActionRunner(
                            new FileSystemWatchingBuildActionRunner(eventEmitter, virtualFileSystem,
                                    new StatStatistics.Collector(),
                                    new FileHasherStatistics.Collector(),
                                    directorySnapshotterStatisticsCollector, buildOperationRunner,

                                    new BuildOutcomeReportingBuildActionRunner(
                                            styledTextOutputFactory, workValidationWarningReporter,
                                            listenerManager,
                                            new ChainingBuildActionRunner(buildActionRunners),
                                            buildStartedTime, buildRequestMetaData, clock)),
                            new GradleEnterprisePluginManager()));
        }
    }
}
