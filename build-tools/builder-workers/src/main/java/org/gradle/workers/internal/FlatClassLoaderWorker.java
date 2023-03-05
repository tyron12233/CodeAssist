package org.gradle.workers.internal;

import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.service.ServiceRegistry;

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
