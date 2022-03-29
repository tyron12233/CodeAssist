package com.tyron.builder.api.internal.reflect.service.scopes;


import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;

/**
 * A hierarchical service registry.
 */
public interface ServiceRegistryFactory {
    /**
     * Creates the services for the given domain object.
     *
     * @param domainObject The domain object.
     * @return The registry containing the services for the domain object.
     */
    ServiceRegistry createFor(Object domainObject);
}