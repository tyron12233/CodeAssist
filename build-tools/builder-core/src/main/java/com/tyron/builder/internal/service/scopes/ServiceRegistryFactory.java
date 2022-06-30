package com.tyron.builder.internal.service.scopes;


import com.tyron.builder.internal.service.ServiceRegistry;

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