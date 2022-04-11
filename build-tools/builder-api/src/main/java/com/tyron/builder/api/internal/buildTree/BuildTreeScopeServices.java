package com.tyron.builder.api.internal.buildTree;

import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;

public class BuildTreeScopeServices {

    private final BuildTreeState buildTree;
    private final BuildTreeModelControllerServices.Supplier modelServices;

    public BuildTreeScopeServices(BuildTreeState buildTree, BuildTreeModelControllerServices.Supplier modelServices) {
        this.buildTree = buildTree;
        this.modelServices = modelServices;
    }

    protected void configure(ServiceRegistration registration) {
//        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceRegistries) {
//            pluginServiceRegistry.registerBuildTreeServices(registration);
//        }
        registration.add(BuildTreeState.class, buildTree);
//        registration.add(GradleEnterprisePluginManager.class);
//        registration.add(DefaultBuildLifecycleControllerFactory.class);
//        registration.add(BuildOptionBuildOperationProgressEventsEmitter.class);
//        registration.add(BuildInclusionCoordinator.class);
        modelServices.applyServicesTo(registration);
    }

//    protected DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
//        return parent.createChild(Scopes.BuildTree.class);
//    }

//    protected ProblemReporter createProblemReporter() {
//        return new DeprecationsReporter();
//    }
}
