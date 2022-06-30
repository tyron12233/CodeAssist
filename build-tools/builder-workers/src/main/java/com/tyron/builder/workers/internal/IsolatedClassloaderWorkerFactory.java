package com.tyron.builder.workers.internal;

import com.tyron.builder.initialization.ClassLoaderRegistry;
import com.tyron.builder.initialization.LegacyTypesSupport;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.process.internal.worker.RequestHandler;

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
