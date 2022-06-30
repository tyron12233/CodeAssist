package com.tyron.builder.process.internal.worker;

/**
 * Configures and builds single request workers. A single request worker runs each request in a separate forked worker process.
 *
 * <p>This builder produces instances of type {@link RequestHandler}. Each call to {@link RequestHandler#run(Object)} on the returned object will start a worker process,
 * run the method in the worker and will block until the result is received by the caller and the worker process has stopped.
 * Any exception thrown by the worker method is rethrown to the caller.
 *
 * <p>The worker process executes the request using an instance of the implementation type specified as a parameter to {@link WorkerProcessFactory#singleRequestWorker(Class)}.</p>
 */
public interface SingleRequestWorkerProcessBuilder<IN, OUT> extends WorkerProcessSettings {
    /**
     * Creates the worker. The returned value can be used to run multiple requests, each will run in a separate worker process.
     */
    RequestHandler<IN, OUT> build();
}
