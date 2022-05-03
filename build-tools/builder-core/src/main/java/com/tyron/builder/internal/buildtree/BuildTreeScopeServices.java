package com.tyron.builder.internal.buildtree;

import com.tyron.builder.api.internal.project.DefaultProjectStateRegistry;
import com.tyron.builder.execution.MultipleBuildFailures;
import com.tyron.builder.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.work.WorkerLeaseService;

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

        registration.add(DefaultBuildLifecycleControllerFactory.class);
        registration.add(BuildTreeState.class, buildTree);
//        registration.add(GradleEnterprisePluginManager.class);
//        registration.add(BuildOptionBuildOperationProgressEventsEmitter.class);
        registration.add(BuildInclusionCoordinator.class);
        modelServices.applyServicesTo(registration);


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



}
