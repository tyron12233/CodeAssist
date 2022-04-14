package com.tyron.builder.internal.buildTree;

import com.tyron.builder.api.execution.MultipleBuildFailures;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.invocation.BuildAction;
import com.tyron.builder.api.internal.operations.BuildOperationRunner;
import com.tyron.builder.api.internal.project.DefaultProjectStateRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.configurationcache.DefaultBuildModelControllerServices;
import com.tyron.builder.configurationcache.DefaultBuildToolingModelControllerFactory;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.watch.registry.WatchMode;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.VfsLogging;
import com.tyron.builder.internal.watch.vfs.WatchLogging;
import com.tyron.builder.launcher.exec.ChainingBuildActionRunner;
import com.tyron.builder.launcher.exec.RootBuildLifecycleBuildActionExecutor;

import java.util.List;

import javax.annotation.Nullable;

public class BuildTreeScopeServices {

    private final BuildTreeState buildTree;
    private final BuildTreeModelControllerServices.Supplier modelServices;

    public BuildTreeScopeServices(BuildTreeState buildTree, BuildTreeModelControllerServices.Supplier modelServices) {
        this.buildTree = buildTree;
        this.modelServices = modelServices;
    }

    protected void configure(ServiceRegistration registration, List<PluginServiceRegistry> pluginServiceRegistries) {

        // from ExecutionServices
        registration.add(DefaultPlanExecutor.class);

//        // from ToolingBuildTreeScopeServices
//        registration.addProvider(new Object() {
//            BuildTreeActionExecutor createActionExecutor(
//                    BuildStateRegistry buildStateRegistry
//            ) {
//                return new RootBuildLifecycleBuildActionExecutor(
//                        buildStateRegistry,
//                        new ChainingBuildActionRunner(Collections.emptyList())
//                );
//            }
//        });
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceRegistries) {
            pluginServiceRegistry.registerBuildTreeServices(registration);
        }
        registration.add(DefaultBuildToolingModelControllerFactory.class);
        registration.add(DefaultBuildLifecycleControllerFactory.class);
        registration.add(BuildTreeState.class, buildTree);
//        registration.add(GradleEnterprisePluginManager.class);
//        registration.add(BuildOptionBuildOperationProgressEventsEmitter.class);
        registration.add(BuildInclusionCoordinator.class);
        modelServices.applyServicesTo(registration);

        registration.add(DefaultBuildModelControllerServices.class);


    }

    ExceptionAnalyser createExceptionanalyser() {
        return new ExceptionAnalyser() {
            @Override
            public RuntimeException transform(Throwable failure) {
                if (failure instanceof RuntimeException) {
                    return (RuntimeException) failure;
                }
                return UncheckedException.throwAsUncheckedException(failure);
            }

            @Nullable
            @Override
            public RuntimeException transform(List<Throwable> failures) {
                return new MultipleBuildFailures(failures);
            }
        };
    }

    protected DefaultProjectStateRegistry createProjectPathRegistry(WorkerLeaseService workerLeaseService) {
        return new DefaultProjectStateRegistry(workerLeaseService);
    }

    protected DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
        return parent.createChild(Scopes.BuildTree.class);
    }

//    protected ProblemReporter createProblemReporter() {
//        return new DeprecationsReporter();
//    }

    BuildTreeActionExecutor createActionExecutor(
            List<BuildActionRunner> buildActionRunners,
            BuildStateRegistry buildStateRegistry

    ){
        return new RootBuildLifecycleBuildActionExecutor(
                buildStateRegistry,
                new ChainingBuildActionRunner(buildActionRunners)
        );
    }

    ExecuteBuildActionRunner createExecuteBuildActionRunner(
            VirtualFileSystem vfs,
            BuildOperationRunner buildOperationRunner
    ) {
        return new ExecuteBuildActionRunner(vfs, buildOperationRunner);
    }

    public static class ExecuteBuildActionRunner implements BuildActionRunner {

        private final VirtualFileSystem vfs;
        private final BuildOperationRunner buildOperationRunner;

        public ExecuteBuildActionRunner(VirtualFileSystem vfs,
                                        BuildOperationRunner buildOperationRunner) {
            this.vfs = vfs;
            this.buildOperationRunner = buildOperationRunner;
        }

        @Override
        public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
//            if (!(action instanceof ExecuteBuildAction)) {
//                return Result.nothing();
//            }
            if (vfs instanceof BuildLifecycleAwareVirtualFileSystem) {
                ((BuildLifecycleAwareVirtualFileSystem) vfs).afterBuildStarted(
                        WatchMode.ENABLED,
                        VfsLogging.VERBOSE, WatchLogging.DEBUG,
                        buildOperationRunner
                );
            }
            try {
                buildController.scheduleAndRunTasks();
                return Result.of(null);
            } catch (RuntimeException e) {
                return Result.failed(e);
            } finally {
                if (vfs instanceof BuildLifecycleAwareVirtualFileSystem) {
                    ((BuildLifecycleAwareVirtualFileSystem) vfs).beforeBuildFinished(
                            WatchMode.ENABLED,
                            VfsLogging.VERBOSE,
                            WatchLogging.DEBUG,
                            buildOperationRunner,
                            Integer.MAX_VALUE
                    );
                }
            }
        }
    }

}
