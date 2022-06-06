package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.serialize.Serializer;

/**
 * Configures and builds multi-request workers. A multi-request worker runs zero or more requests in a forked worker process.
 *
 * <p>This builder produces instances of type {@link MultiRequestClient}. Each call to {@link MultiRequestClient#run(Object)} on the returned object will run the method in the worker and block until the result is received.
 * Any exception thrown by the worker method is rethrown to the caller.
 *
 * <p>The worker process executes the request using an instance of the implementation type specified as a parameter to {@link WorkerProcessFactory#multiRequestWorker(Class)}.</p>
 *
 * <p>The worker process must be explicitly started and stopped using the methods on {@link WorkerControl}.</p>
 */
public interface MultiRequestWorkerProcessBuilder<IN, OUT> extends WorkerProcessSettings {
    /**
     * Creates a worker.
     *
     * <p>The worker process is not started until {@link WorkerControl#start()} is called on the returned object.</p>
     */
    MultiRequestClient<IN, OUT> build();

    /**
     * Registers a callback to invoke if a failure in an underlying process is detected.
     */
    void onProcessFailure(Action<WorkerProcess> action);

    /**
     * Registers a serializer to use when handling arguments to methods of {@link T}.
     */
    <T> void registerArgumentSerializer(Class<T> type, Serializer<T> serializer);

    /**
     * Use a simpler classloader structure where everything is in the application classloader.
     */
    void useApplicationClassloaderOnly();
}
