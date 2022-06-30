package com.tyron.builder.internal.buildtree;

import com.tyron.builder.api.internal.project.DefaultProjectStateRegistry;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.execution.MultipleBuildFailures;
import com.tyron.builder.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.initialization.exception.DefaultExceptionAnalyser;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.exception.MultipleBuildFailuresExceptionAnalyser;
import com.tyron.builder.initialization.exception.StackTraceSanitizingExceptionAnalyser;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.build.DefaultBuildLifecycleControllerFactory;
import com.tyron.builder.internal.enterprise.core.GradleEnterprisePluginManager;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.problems.buildtree.ProblemReporter;

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
        for (PluginServiceRegistry pluginServiceRegistry : pluginServiceRegistries) {
            pluginServiceRegistry.registerBuildTreeServices(registration);
        }

        registration.add(DefaultBuildLifecycleControllerFactory.class);
        registration.add(BuildTreeState.class, buildTree);
        registration.add(GradleEnterprisePluginManager.class);
//        registration.add(BuildOptionBuildOperationProgressEventsEmitter.class);
        registration.add(BuildInclusionCoordinator.class);
        modelServices.applyServicesTo(registration);


    }

    protected ExceptionAnalyser createExceptionAnalyser(ListenerManager listenerManager, LoggingConfiguration loggingConfiguration) {
        ExceptionAnalyser exceptionAnalyser = new MultipleBuildFailuresExceptionAnalyser(new DefaultExceptionAnalyser(listenerManager));
        if (loggingConfiguration.getShowStacktrace() != ShowStacktrace.ALWAYS_FULL) {
            exceptionAnalyser = new StackTraceSanitizingExceptionAnalyser(exceptionAnalyser);
        }
        return exceptionAnalyser;
    }

    protected DefaultProjectStateRegistry createProjectPathRegistry(WorkerLeaseService workerLeaseService) {
        return new DefaultProjectStateRegistry(workerLeaseService);
    }

    protected DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
        return parent.createChild(Scopes.BuildTree.class);
    }

    protected ProblemReporter createProblemReporter() {
        return new DeprecationsReporter();
    }



}
