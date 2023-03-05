package org.gradle.tooling.internal.launcher;

import static java.util.Collections.emptyList;

import org.gradle.api.execution.internal.TaskInputsListeners;
import org.gradle.api.internal.changedetection.state.FileHasherStatistics;
import org.gradle.execution.WorkValidationWarningReporter;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.BuildEventConsumer;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.initialization.exception.ExceptionAnalyser;
import org.gradle.initialization.layout.BuildLayout;
import org.gradle.internal.build.BuildLayoutValidator;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.event.BuildEventListenerFactory;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.buildevents.BuildStartedTime;
import org.gradle.internal.buildtree.BuildActionRunner;
import org.gradle.internal.buildtree.BuildTreeActionExecutor;
import org.gradle.internal.buildtree.BuildTreeModelControllerServices;
import org.gradle.internal.classpath.CachedClasspathTransformer;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.file.StatStatistics;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationListenerManager;
import org.gradle.internal.operations.BuildOperationProgressEventEmitter;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.operations.logging.LoggingBuildOperationProgressBroadcaster;
import org.gradle.internal.operations.notify.BuildOperationNotificationValve;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import org.gradle.internal.session.BuildSessionActionExecutor;
import org.gradle.internal.snapshot.impl.DirectorySnapshotterStatistics;
import org.gradle.internal.time.Clock;
import org.gradle.internal.time.Time;
import org.gradle.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.launcher.exec.BuildCompletionNotifyingBuildActionRunner;
import org.gradle.launcher.exec.BuildExecuter;
import org.gradle.launcher.exec.BuildTreeLifecycleBuildActionExecutor;
import org.gradle.launcher.exec.ChainingBuildActionRunner;
import org.gradle.launcher.exec.RootBuildLifecycleBuildActionExecutor;
import org.gradle.launcher.exec.RunAsBuildOperationBuildActionExecutor;
import org.gradle.launcher.exec.RunAsWorkerThreadBuildActionExecutor;
import org.gradle.tooling.internal.provider.BuildOutcomeReportingBuildActionRunner;
import org.gradle.tooling.internal.provider.BuildSessionLifecycleBuildActionExecuter;
import org.gradle.tooling.internal.provider.ExecuteBuildActionRunner;
import org.gradle.tooling.internal.provider.FileSystemWatchingBuildActionRunner;
import org.gradle.tooling.internal.provider.SessionFailureReportingActionExecuter;
import org.gradle.tooling.internal.provider.SetupLoggingActionExecuter;
import org.gradle.tooling.internal.provider.StartParamsValidatingActionExecuter;
import org.gradle.tooling.internal.provider.serialization.ClassLoaderCache;
import org.gradle.tooling.internal.provider.serialization.DaemonSidePayloadClassLoaderFactory;
import org.gradle.tooling.internal.provider.serialization.DefaultPayloadClassLoaderRegistry;
import org.gradle.tooling.internal.provider.serialization.ModelClassLoaderFactory;
import org.gradle.tooling.internal.provider.serialization.PayloadClassLoaderFactory;
import org.gradle.tooling.internal.provider.serialization.PayloadSerializer;
import org.gradle.tooling.internal.provider.serialization.WellKnownClassLoaderRegistry;

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

        BuildExecuter createBuildExecuter(
                StyledTextOutputFactory styledTextOutputFactory,
                LoggingManagerInternal loggingManager,
                WorkValidationWarningReporter workValidationWarningReporter,
                GradleUserHomeScopeServiceRegistry userHomeServiceRegistry,
                ServiceRegistry globalServices
        ) {
            // @formatter:off
            return
                    new SetupLoggingActionExecuter(loggingManager,
                            new SessionFailureReportingActionExecuter(styledTextOutputFactory, Time.clock(), workValidationWarningReporter,
                                    new StartParamsValidatingActionExecuter(
                                            new BuildSessionLifecycleBuildActionExecuter(userHomeServiceRegistry, globalServices
                                            ))));
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
                BuildEventListenerFactory listenerFactory,
                ExecutorFactory executorFactory,
                ListenerManager listenerManager,
                BuildOperationListenerManager buildOperationListenerManager,
                BuildOperationExecutor buildOperationExecutor,
                TaskInputsListeners inputsListeners,
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
//                                                     GradleEnterprisePluginManager gradleEnterprisePluginManager,
                                                     BuildLifecycleAwareVirtualFileSystem virtualFileSystem,
//                                                     StatStatistics.Collector statStatisticsCollector,
//                                                     FileHasherStatistics.Collector fileHasherStatisticsCollector,
                                                     DirectorySnapshotterStatistics.Collector directorySnapshotterStatisticsCollector,
                                                     BuildOperationRunner buildOperationRunner,
                                                     Clock clock
//                                                     BuildLayout buildLayout,
//                                                     ExceptionAnalyser exceptionAnalyser
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
