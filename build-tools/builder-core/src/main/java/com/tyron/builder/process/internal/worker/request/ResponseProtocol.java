package com.tyron.builder.process.internal.worker.request;

import com.tyron.builder.process.internal.worker.child.WorkerLoggingProtocol;

public interface ResponseProtocol extends WorkerLoggingProtocol {
    void completed(Object result);

    // Called when the method throws an exception
    void failed(Throwable failure);

    // Called when some other problem occurs
    void infrastructureFailed(Throwable failure);
}
