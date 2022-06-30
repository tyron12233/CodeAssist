package com.tyron.builder.process.internal.worker;

public interface MultiRequestClient<IN, OUT> extends RequestHandler<IN, OUT>, WorkerControl {
}
