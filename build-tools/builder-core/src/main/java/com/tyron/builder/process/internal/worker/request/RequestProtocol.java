package com.tyron.builder.process.internal.worker.request;

public interface RequestProtocol {
    void run(Request request);
    void runThenStop(Request request);
    void stop();
}
