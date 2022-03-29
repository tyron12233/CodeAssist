package com.tyron.builder.api.internal.reflect.service;

/**
 * Allows services to be added to a registry.
 */
public interface ServiceRegistration {
    /**
     * Adds a service to this registry. The given object is closed when the associated registry is closed.
     * @param serviceType The type to make this service visible as.
     * @param serviceInstance The service implementation.
     */
    <T> void add(Class<T> serviceType, T serviceInstance);

    /**
     * Adds a service to this registry. The implementation class should have a single public constructor, and this constructor can take services to be injected as parameters.
     *
     * @param serviceType The service implementation to make visible.
     */
    void add(Class<?> serviceType);

    /**
     * Adds a service provider bean to this registry. This provider may define factory and decorator methods. See {@link DefaultServiceRegistry} for details.
     */
    void addProvider(Object provider);
}