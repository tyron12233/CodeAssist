package com.tyron.builder.api.internal.reflect.service;

import java.util.ArrayList;
import java.util.List;

public class ServiceRegistryBuilder {
    private final List<ServiceRegistry> parents = new ArrayList<ServiceRegistry>();
    private final List<Object> providers = new ArrayList<Object>();
    private String displayName;

    private ServiceRegistryBuilder() {
    }

    public static ServiceRegistryBuilder builder() {
        return new ServiceRegistryBuilder();
    }

    public ServiceRegistryBuilder displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public ServiceRegistryBuilder parent(ServiceRegistry parent) {
        this.parents.add(parent);
        return this;
    }

    public ServiceRegistryBuilder provider(Object provider) {
        this.providers.add(provider);
        return this;
    }

    public ServiceRegistry build() {
        DefaultServiceRegistry registry = new DefaultServiceRegistry(displayName, parents.toArray(new ServiceRegistry[0]));
        for (Object provider : providers) {
            registry.addProvider(provider);
        }
        return registry;
    }
}