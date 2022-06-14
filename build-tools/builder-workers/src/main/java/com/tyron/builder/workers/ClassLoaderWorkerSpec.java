package com.tyron.builder.workers;

import com.tyron.builder.api.file.ConfigurableFileCollection;

/**
 * A worker spec providing the requirements of an isolated classpath.
 *
 * @since 5.6
 */
public interface ClassLoaderWorkerSpec extends WorkerSpec {
    /**
     * Gets the classpath associated with the worker.
     *
     * @return the classpath associated with the worker
     */
    ConfigurableFileCollection getClasspath();
}
