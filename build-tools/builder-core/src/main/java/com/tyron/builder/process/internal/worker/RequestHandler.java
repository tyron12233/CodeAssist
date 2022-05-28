package com.tyron.builder.process.internal.worker;

import java.io.Serializable;

/**
 * Handles requests to do work in worker process. Is instantiated in the build process and serialized to the worker process.
 */
public interface RequestHandler<IN, OUT> extends Serializable {
    /**
     * Executes the given request and returns the response. Called in the worker process only.
     */
    OUT run(IN request);
}
