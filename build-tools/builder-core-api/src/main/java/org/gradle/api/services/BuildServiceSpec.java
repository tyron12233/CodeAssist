package org.gradle.api.services;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.provider.Property;

/**
 * A set of parameters that defines a service registration.
 *
 * @param <P> The type of parameters to inject into the service implementation.
 * @since 6.1
 */
@Incubating
public interface BuildServiceSpec<P extends BuildServiceParameters> {
    /**
     * Returns the parameters to will be used to create the service instance.
     */
    P getParameters();

    /**
     * Runs the given action to configure the parameters.
     */
    void parameters(Action<? super P> configureAction);

    /**
     * Specifies the maximum number of tasks that can use this service in parallel. Setting this to 1 means that the service will be used by a single task at a time.
     * When this property has no value defined, then any number of tasks may use this service in parallel. This is the default.
     */
    Property<Integer> getMaxParallelUsages();
}
