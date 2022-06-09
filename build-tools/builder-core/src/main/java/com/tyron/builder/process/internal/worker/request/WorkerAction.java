package com.tyron.builder.process.internal.worker.request;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.tasks.properties.annotations.OutputPropertyRoleAnnotationHandler;
import com.tyron.builder.cache.internal.DefaultCrossBuildInMemoryCacheFactory;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.dispatch.StreamCompletion;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.instantiation.generator.DefaultInstantiatorFactory;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.internal.remote.internal.hub.StreamFailureHandler;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.Scope.Global;
import com.tyron.builder.process.internal.worker.RequestHandler;
import com.tyron.builder.process.internal.worker.WorkerProcessContext;
import com.tyron.builder.process.internal.worker.child.WorkerLogEventListener;

import java.io.Serializable;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

public class WorkerAction implements Action<WorkerProcessContext>, Serializable, RequestProtocol, StreamFailureHandler, Stoppable, StreamCompletion {
    private final String workerImplementationName;
    private transient CountDownLatch completed;
    private transient ResponseProtocol responder;
    private transient WorkerLogEventListener workerLogEventListener;
    private transient RequestHandler<Object, Object> implementation;
    private transient InstantiatorFactory instantiatorFactory;
    private transient Exception failure;

    public WorkerAction(Class<?> workerImplementation) {
        this.workerImplementationName = workerImplementation.getName();
    }

    @Override
    public void execute(WorkerProcessContext workerProcessContext) {
        completed = new CountDownLatch(1);

        RequestArgumentSerializers argumentSerializers = new RequestArgumentSerializers();
        try {
            ServiceRegistry parentServices = workerProcessContext.getServiceRegistry();
            if (instantiatorFactory == null) {
                instantiatorFactory = new DefaultInstantiatorFactory(new DefaultCrossBuildInMemoryCacheFactory(new DefaultListenerManager(Global.class)), Collections.emptyList(), new OutputPropertyRoleAnnotationHandler(Collections.emptyList()));
            }
            DefaultServiceRegistry serviceRegistry = new DefaultServiceRegistry("worker-action-services", parentServices);
            // Make the argument serializers available so work implementations can register their own serializers
            serviceRegistry.add(RequestArgumentSerializers.class, argumentSerializers);
            serviceRegistry.add(InstantiatorFactory.class, instantiatorFactory);
            Class<?> workerImplementation = Class.forName(workerImplementationName);
            implementation = Cast.uncheckedNonnullCast(instantiatorFactory.inject(serviceRegistry).newInstance(workerImplementation));
        } catch (Exception e) {
            failure = e;
        }

        ObjectConnection connection = workerProcessContext.getServerConnection();
        connection.addIncoming(RequestProtocol.class, this);
        responder = connection.addOutgoing(ResponseProtocol.class);
        workerLogEventListener = workerProcessContext.getServiceRegistry().get(WorkerLogEventListener.class);
        if (failure == null) {
            connection.useParameterSerializers(RequestSerializerRegistry.create(this.getClass().getClassLoader(), argumentSerializers));
        } else {
            // Discard incoming requests, as the serializers may not have been configured
            connection.useParameterSerializers(RequestSerializerRegistry.createDiscardRequestArg());
            // Notify the client
            responder.infrastructureFailed(failure);
        }

        connection.connect();

        try {
            completed.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void stop() {
        completed.countDown();
        CurrentBuildOperationRef.instance().clear();
    }

    @Override
    public void endStream() {
        // This happens when the connection between the worker and the build daemon is closed for some reason,
        // possibly because the build daemon died unexpectedly.
        stop();
    }

    @Override
    public void runThenStop(Request request) {
        try {
            run(request);
        } finally {
            stop();
        }
    }

    @Override
    public void run(Request request) {
        if (failure != null) {
            // Ignore
            return;
        }
        try {
            CurrentBuildOperationRef.instance().set(request.getBuildOperation());
            Object result;
            try {
                // We want to use the responder as the logging protocol object here because log messages from the
                // action will have the build operation associated.  By using the responder, we ensure that all
                // messages arrive on the same incoming queue in the build process and the completed message will only
                // arrive after all log messages have been processed.
                result = workerLogEventListener.withWorkerLoggingProtocol(responder, new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return implementation.run(request.getArg());
                    }
                });
            } catch (Throwable failure) {
                if (failure instanceof NoClassDefFoundError) {
                    // Assume an infrastructure problem
                    responder.infrastructureFailed(failure);
                } else {
                    responder.failed(failure);
                }
                return;
            }
            responder.completed(result);
        } catch (Throwable t) {
            responder.infrastructureFailed(t);
        } finally {
            CurrentBuildOperationRef.instance().clear();
        }
    }

    @Override
    public void handleStreamFailure(Throwable t) {
        responder.failed(t);
    }
}
