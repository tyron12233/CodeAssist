package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.service.ServiceRegistry;

public class FlatClassLoaderWorker extends AbstractClassLoaderWorker {
    private final ClassLoader workerClassLoader;

    public FlatClassLoaderWorker(ClassLoader workerClassLoader, ServiceRegistry serviceRegistry, ActionExecutionSpecFactory actionExecutionSpecFactory, InstantiatorFactory instantiatorFactory) {
        super(serviceRegistry, actionExecutionSpecFactory, instantiatorFactory);
        this.workerClassLoader = workerClassLoader;
    }

    @Override
    public DefaultWorkResult run(TransportableActionExecutionSpec spec) {
        return executeInClassLoader(spec, workerClassLoader);
    }
}
