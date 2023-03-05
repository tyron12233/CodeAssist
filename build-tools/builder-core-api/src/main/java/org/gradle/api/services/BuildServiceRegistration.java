package org.gradle.api.services;

import org.gradle.api.Incubating;
import org.gradle.api.Named;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

/**
 * Details of a build service.
 *
 * @param <T> the service type.
 * @param <P> the service parameters type.
 * @since 6.1
 */
@Incubating
public interface BuildServiceRegistration<T extends BuildService<P>, P extends BuildServiceParameters> extends Named {
    /**
     * Returns the parameters that will be used to instantiate the service with.
     */
    P getParameters();

    /**
     * Specifies the maximum number of tasks that can use this service in parallel. Setting this to 1 means that the service will be used by a single task at a time.
     * When this property has no value defined, then any number of tasks may use this service iin parallel. This is the default.
     */
    Property<Integer> getMaxParallelUsages();

    /**
     * Returns a {@link Provider} that will create the service instance when its value is queried.
     */
    Provider<T> getService();
}
