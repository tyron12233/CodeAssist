package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.classloader.ClassLoaderUtils;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolated.IsolationScheme;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;
import com.tyron.builder.workers.WorkerExecutor;

import javax.annotation.Nullable;
import java.util.Collections;

public class NoIsolationWorkerFactory implements WorkerFactory {
    private final BuildOperationExecutor buildOperationExecutor;
    private final ActionExecutionSpecFactory specFactory;
    private final Worker workerServer;
    private WorkerExecutor workerExecutor;

    public NoIsolationWorkerFactory(BuildOperationExecutor buildOperationExecutor, InstantiatorFactory instantiatorFactory, ActionExecutionSpecFactory specFactory, ServiceRegistry internalServices) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.specFactory = specFactory;
        IsolationScheme<WorkAction<?>, WorkParameters> isolationScheme = new IsolationScheme<>(Cast.uncheckedNonnullCast(WorkAction.class), WorkParameters.class, WorkParameters.None.class);
        workerServer = new DefaultWorkerServer(internalServices, instantiatorFactory, isolationScheme, Collections.singleton(WorkerExecutor.class));
    }

    // Attaches the owning WorkerExecutor to this factory
    public void setWorkerExecutor(WorkerExecutor workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    @Override
    public BuildOperationAwareWorker getWorker(WorkerRequirement workerRequirement) {
        final WorkerExecutor workerExecutor = this.workerExecutor;
        final ClassLoader contextClassLoader = ((FixedClassLoaderWorkerRequirement) workerRequirement).getContextClassLoader();
        return new AbstractWorker(buildOperationExecutor) {
            @Override
            public DefaultWorkResult execute(IsolatedParametersActionExecutionSpec<?> spec, BuildOperationRef parentBuildOperation) {
                return executeWrappedInBuildOperation(spec, parentBuildOperation, workSpec -> {
                    DefaultWorkResult result;
                    try {
                        result = ClassLoaderUtils.executeInClassloader(contextClassLoader, new Factory<DefaultWorkResult>() {
                            @Nullable
                            @Override
                            public DefaultWorkResult create() {
                                return workerServer.execute(specFactory.newSimpleSpec(workSpec));
                            }
                        });
                    } finally {
                        //TODO the async work tracker should wait for children of an operation to finish first.
                        //It should not be necessary to call it here.
                        workerExecutor.await();
                    }
                    return result;
                });
            }
        };
    }

    @Override
    public IsolationMode getIsolationMode() {
        return IsolationMode.NONE;
    }
}
