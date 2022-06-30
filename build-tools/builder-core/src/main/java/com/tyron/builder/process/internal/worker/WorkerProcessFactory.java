package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.Action;

public interface WorkerProcessFactory {
    /**
     * Creates a builder for workers that will run the given action. The worker action is serialized to the worker process and executed.
     *
     * <p>The worker process is not started until {@link WorkerProcess#start()} is called.</p>
     *
     * @param workerAction The action to serialize and run in the worker process.
     */
    WorkerProcessBuilder create(Action<? super WorkerProcessContext> workerAction);

    /**
     * Creates a builder for workers that will handle requests using the given worker implementation, with each request executed in a separate worker process.
     *
     * <p>The worker process is not started until a method on the return value of {@link SingleRequestWorkerProcessBuilder#build()} is called.</p>
     *
     * @param workerImplementation The implementation class to run in the worker process.
     */
    <IN, OUT> SingleRequestWorkerProcessBuilder<IN, OUT> singleRequestWorker(Class<? extends RequestHandler<? super IN, ? extends OUT>> workerImplementation);

    /**
     * Creates a builder for workers that will handle requests using the given worker implementation, with a worker process handling zero or more requests.
     * A worker process handles a single request at a time.
     *
     * <p>The worker process is not started until {@link WorkerControl#start()} is called.</p>
     *
     * @param workerImplementation The implementation class to run in the worker process.
     */
    <IN, OUT> MultiRequestWorkerProcessBuilder<IN, OUT> multiRequestWorker(Class<? extends RequestHandler<? super IN, ? extends OUT>> workerImplementation);
}
