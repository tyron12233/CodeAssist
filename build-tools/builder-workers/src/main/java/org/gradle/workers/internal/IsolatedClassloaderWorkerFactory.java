package org.gradle.workers.internal;

import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.LegacyTypesSupport;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationRef;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.process.internal.worker.RequestHandler;

public class IsolatedClassloaderWorkerFactory implements WorkerFactory {
    private final BuildOperationExecutor buildOperationExecutor;
    private final ServiceRegistry internalServices;
    private final ClassLoaderRegistry classLoaderRegistry;
    private final LegacyTypesSupport legacyTypesSupport;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;
    private final InstantiatorFactory instantiatorFactory;

    public IsolatedClassloaderWorkerFactory(BuildOperationExecutor buildOperationExecutor, ServiceRegistry internalServices, ClassLoaderRegistry classLoaderRegistry, LegacyTypesSupport legacyTypesSupport, ActionExecutionSpecFactory actionExecutionSpecFactory, InstantiatorFactory instantiatorFactory) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.internalServices = internalServices;
        this.classLoaderRegistry = classLoaderRegistry;
        this.legacyTypesSupport = legacyTypesSupport;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
        this.instantiatorFactory = instantiatorFactory;
    }

    @Override
    public BuildOperationAwareWorker getWorker(WorkerRequirement workerRequirement) {
        return new AbstractWorker(buildOperationExecutor) {
            @Override
            public DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec, BuildOperationRef parentBuildOperation) {
                return executeWrappedInBuildOperation(spec, parentBuildOperation, workSpec -> {
                    // Serialize the incoming class and parameters
                    TransportableActionExecutionSpec transportableSpec = actionExecutionSpecFactory.newTransportableSpec(spec);

                    ClassLoader workerInfrastructureClassloader = classLoaderRegistry.getPluginsClassLoader();
                    ClassLoaderStructure classLoaderStructure = ((IsolatedClassLoaderWorkerRequirement) workerRequirement).getClassLoaderStructure();
                    ClassLoader workerClassLoader = IsolatedClassloaderWorker.createIsolatedWorkerClassloader(classLoaderStructure, workerInfrastructureClassloader, legacyTypesSupport);
                    RequestHandler<TransportableActionExecutionSpec, DefaultWorkResult> worker = new IsolatedClassloaderWorker(workerClassLoader, internalServices, actionExecutionSpecFactory, instantiatorFactory);
                    return worker.run(transportableSpec);
                });
            }
        };
    }

    @Override
    public IsolationMode getIsolationMode() {
        return IsolationMode.CLASSLOADER;
    }
}
