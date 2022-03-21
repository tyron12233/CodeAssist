package com.tyron.builder.api.internal.service;

import com.tyron.builder.api.internal.Factory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultServiceRegistry implements ServiceRegistry {

    private final Map<Class<?>, Object> SERVICES = new HashMap<>();

    public <T> void addService(Class<T> classType, T service) {
        Object old = SERVICES.put(classType, service);
        if (old != null) {
            throw new IllegalArgumentException("Duplicate service " + classType);
        }
    }

    @Override
    public Object get(Type serviceType,
                      Class<? extends Annotation> annotatedWith) throws UnknownServiceException, ServiceLookupException {
        return null;
    }

    @Override
    public <T> T get(Class<T> serviceType) throws UnknownServiceException, ServiceLookupException {
        //noinspection unchecked
        return (T) SERVICES.get(serviceType);
    }

    @Override
    public <T> List<T> getAll(Class<T> serviceType) throws ServiceLookupException {
        return null;
    }

    @Override
    public Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException {
        return null;
    }

    @Override
    public Object find(Type serviceType) throws ServiceLookupException {
        return null;
    }

    @Override
    public <T> Factory<T> getFactory(Class<T> type) throws UnknownServiceException, ServiceLookupException {
        return null;
    }

    @Override
    public <T> T newInstance(Class<T> type) throws UnknownServiceException, ServiceLookupException {
        return null;
    }
}
