package com.tyron.builder.workers;

/**
 * A worker spec providing the requirements of a forked process with a custom classpath.
 *
 * @since 5.6
 */
public interface ProcessWorkerSpec extends ForkingWorkerSpec, ClassLoaderWorkerSpec {

}
