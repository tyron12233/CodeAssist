package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolated.IsolationScheme;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.process.internal.worker.RequestHandler;
import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;

import javax.annotation.Nullable;
import java.util.Collections;

import static com.tyron.builder.internal.classloader.ClassLoaderUtils.executeInClassloader;

public abstract class AbstractClassLoaderWorker implements RequestHandler<TransportableActionExecutionSpec, DefaultWorkResult> {
    private final Worker worker;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;

    public AbstractClassLoaderWorker(ServiceRegistry workServices, ActionExecutionSpecFactory actionExecutionSpecFactory, InstantiatorFactory instantiatorFactory) {
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
        this.worker = new DefaultWorkerServer(workServices, instantiatorFactory, new IsolationScheme<>(Cast.uncheckedCast(WorkAction.class), WorkParameters.class, WorkParameters.None.class), Collections.emptyList());
    }

    public DefaultWorkResult executeInClassLoader(TransportableActionExecutionSpec spec, ClassLoader workerClassLoader) {
        return executeInClassloader(workerClassLoader, new Factory<DefaultWorkResult>() {
            @Nullable
            @Override
            public DefaultWorkResult create() {
                // Deserialize the class and parameters in the workerClassLoader (the context classloader)
                SimpleActionExecutionSpec<?> effectiveSpec = actionExecutionSpecFactory.newSimpleSpec(spec);
                return worker.execute(effectiveSpec);
            }
        });
    }
}
