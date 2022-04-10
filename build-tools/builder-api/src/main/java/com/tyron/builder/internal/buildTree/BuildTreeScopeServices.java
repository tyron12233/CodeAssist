package com.tyron.builder.internal.buildTree;

import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.project.DefaultProjectStateRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.composite.internal.CompositeBuildServices;
import com.tyron.builder.configurationcache.DefaultBuildModelControllerServices;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;

import java.util.ArrayList;
import java.util.List;

public class BuildTreeScopeServices {

    private final BuildTreeState buildTree;
    private final BuildTreeModelControllerServices.Supplier modelServices;

    public BuildTreeScopeServices(BuildTreeState buildTree, BuildTreeModelControllerServices.Supplier modelServices) {
        this.buildTree = buildTree;
        this.modelServices = modelServices;
    }

    protected void configure(ServiceRegistration registration) {
        List<PluginServiceRegistry> pluginServiceRegistries = new ArrayList<>();
        pluginServiceRegistries.add(new CompositeBuildServices());

        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceRegistries) {
            pluginServiceRegistry.registerBuildTreeServices(registration);
        }
        registration.add(BuildTreeState.class, buildTree);
//        registration.add(GradleEnterprisePluginManager.class);
//        registration.add(DefaultBuildLifecycleControllerFactory.class);
//        registration.add(BuildOptionBuildOperationProgressEventsEmitter.class);
        registration.add(BuildInclusionCoordinator.class);
        modelServices.applyServicesTo(registration);

        registration.add(DefaultBuildModelControllerServices.class);
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
