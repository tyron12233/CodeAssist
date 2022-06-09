package com.tyron.builder.deployment.internal;

import javax.inject.Inject;

/**
 * Controls starting and stopping a deployment.
 *
 * Implementations of this interface should annotate at least one constructor with {@link Inject}, if
 * the implementation requires parameters.
 *
 * @since 4.2
 */
public interface DeploymentHandle {
    /**
     * Returns true if the deployment is still running.
     */
    boolean isRunning();

    /**
     * Starts the given deployment.
     * @param deployment the deployment to be started
     */
    void start(Deployment deployment);

    /**
     * Stops the deployment.
     */
    void stop();
}
