package org.gradle.process.internal.worker;

public interface MultiRequestClient<IN, OUT> extends RequestHandler<IN, OUT>, WorkerControl {
}
