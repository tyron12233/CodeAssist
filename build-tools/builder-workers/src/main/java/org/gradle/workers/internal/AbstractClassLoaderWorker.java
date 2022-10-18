package org.gradle.workers.internal;

import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolated.IsolationScheme;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.process.internal.worker.RequestHandler;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import javax.annotation.Nullable;
import java.util.Collections;

import static org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader;

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
