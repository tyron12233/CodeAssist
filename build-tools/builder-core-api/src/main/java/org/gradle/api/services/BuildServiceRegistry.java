package org.gradle.api.services;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Provider;

/**
 * A registry of build services. You use this type to register service instances.
 *
 * <p>A registry is available using {@link Gradle#getSharedServices()}.</p>
 *
 * @since 6.1
 */
@Incubating
public interface BuildServiceRegistry {
    /**
     * Returns the set of service registrations.
     */
    NamedDomainObjectSet<BuildServiceRegistration<?, ?>> getRegistrations();

    /**
     * Registers a service, if a service with the given name is not already registered. The service is not created until required, when the returned {@link Provider} is queried.
     *
     * @param name A name to use to identify the service.
     * @param implementationType The service implementation type. Instances of the service are created as for {@link org.gradle.api.model.ObjectFactory#newInstance(Class, Object...)}.
     * @param configureAction An action to configure the registration. You can use this to provide parameters to the service instance.
     * @return A {@link Provider} that will create the service instance when queried.
     */
    <T extends BuildService<P>, P extends BuildServiceParameters> Provider<T> registerIfAbsent(String name, Class<T> implementationType, Action<? super BuildServiceSpec<P>> configureAction);
}
