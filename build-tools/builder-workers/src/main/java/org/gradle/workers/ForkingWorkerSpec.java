package org.gradle.workers;

import org.gradle.api.Action;
import org.gradle.process.JavaForkOptions;

/**
 * A worker spec providing the requirements of a forked process.
 *
 * @since 5.6
 */
public interface ForkingWorkerSpec extends WorkerSpec {
    /**
     * Executes the provided action against the {@link JavaForkOptions} object associated with this builder.
     *
     * @param forkOptionsAction - An action to configure the {@link JavaForkOptions} for this builder
     */
    void forkOptions(Action<? super JavaForkOptions> forkOptionsAction);

    /**
     * Returns the {@link JavaForkOptions} object associated with this builder.
     *
     * @return the {@link JavaForkOptions} of this builder
     */
    JavaForkOptions getForkOptions();
}
