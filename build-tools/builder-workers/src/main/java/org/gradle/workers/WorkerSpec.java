package org.gradle.workers;

import org.gradle.internal.HasInternalProtocol;

/**
 * Represents the common configuration of a worker.  Used when submitting an item of work
 * to the {@link WorkerExecutor}.
 *
 * @since 5.6
 */
@HasInternalProtocol
public interface WorkerSpec {
}
