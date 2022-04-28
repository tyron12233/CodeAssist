package com.tyron.builder.tooling.internal.launcher;

import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.buildTree.BuildActionRunner;
import com.tyron.builder.internal.buildTree.BuildTreeActionExecutor;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildTree.BuildTreeScopeServices;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.operations.BuildOperationRunner;
import com.tyron.builder.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionActionExecutor;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.watch.registry.WatchMode;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.VfsLogging;
import com.tyron.builder.internal.watch.vfs.WatchLogging;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.launcher.exec.BuildTreeLifecycleBuildActionExecutor;
import com.tyron.builder.launcher.exec.ChainingBuildActionRunner;
import com.tyron.builder.launcher.exec.RootBuildLifecycleBuildActionExecutor;
import com.tyron.builder.launcher.exec.RunAsWorkerThreadBuildActionExecutor;

import java.util.List;

public class LauncherServices extends AbstractPluginServiceRegistry {

    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new ToolingGlobalScopeServices());
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
        ExecuteBuildActionRunner createExecuteBuildActionRunner(
                VirtualFileSystem vfs,
                BuildOperationRunner buildOperationRunner
        ) {
            return new ExecuteBuildActionRunner(vfs, buildOperationRunner);
        }
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

    static class ToolingBuildSessionScopeServices {
        BuildSessionActionExecutor createActionExecutor(
                BuildTreeModelControllerServices buildModelServices,
                BuildLayoutValidator buildLayoutValidator,
                WorkerLeaseService workerLeaseService
        ) {
            return new RunAsWorkerThreadBuildActionExecutor(
                    workerLeaseService,
                    new BuildTreeLifecycleBuildActionExecutor(buildModelServices, buildLayoutValidator)
            );
        }
    }

    static class ToolingBuildTreeScopeServices {
        BuildTreeActionExecutor createActionExecutor(
                List<BuildActionRunner> buildActionRunners,
                BuildStateRegistry buildStateRegistry

        ){
            return new RootBuildLifecycleBuildActionExecutor(
                    buildStateRegistry,
                    new ChainingBuildActionRunner(buildActionRunners)
            );
        }
    }
}
