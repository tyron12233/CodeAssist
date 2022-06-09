package com.tyron.builder.deployment.internal;

import com.tyron.builder.internal.HasInternalProtocol;

/**
 * A deployed application.
 *
 * @since 4.2
 */
@HasInternalProtocol
public interface Deployment {
    /**
     * Returns the latest status for this deployment.
     *
     * <p>
     * This method may block until all pending changes have been incorporated.
     * </p>
     * @return the current status of this deployment.
     */
    Status status();

    /**
     * Status of a Deployment
     */
    interface Status {
        /**
         * Returns a Throwable if the latest build failed for this deployment.
         * @return any failure for the current status.
         */
        Throwable getFailure();

        /**
         * Returns true if the deployment's runtime may have changed since the previous status was reported.
         * @return whether the deployment runtime may have changed.
         */
        boolean hasChanged();
    }
}
