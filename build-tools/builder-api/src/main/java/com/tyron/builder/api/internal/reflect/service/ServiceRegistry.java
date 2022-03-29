package com.tyron.builder.api.internal.reflect.service;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.Factory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * A read-only registry of services. May or may not be immutable.
 */
public interface ServiceRegistry extends ServiceLookup {
    /**
     * Locates the service of the given type.
     *
     * @param serviceType The service type.
     * @param <T>         The service type.
     * @return The service instance. Never returns null.
     * @throws UnknownServiceException When there is no service of the given type available.
     * @throws ServiceLookupException On failure to lookup the specified service.
     */
//    @UsedByScanPlugin("scan, test-retry")
    <T> T get(Class<T> serviceType) throws UnknownServiceException, ServiceLookupException;

    /**
     * Locates all services of the given type.
     *
     * @param serviceType The service type.
     * @param <T>         The service type.
     * @throws ServiceLookupException On failure to lookup the specified service.
     */
    <T> List<T> getAll(Class<T> serviceType) throws ServiceLookupException;

    /**
     * Locates the service of the given type.
     *
     * @param serviceType The service type.
     * @return The service instance. Never returns null.
     * @throws UnknownServiceException When there is no service of the given type available.
     * @throws ServiceLookupException On failure to lookup the specified service.
     */
    @Override
    Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException;

    /**
     * Locates the service of the given type, returning null if no such service.
     *
     * @param serviceType The service type.
     * @return The service instance. Returns {@code null} if no such service exists.
     * @throws ServiceLookupException On failure to lookup the specified service.
     */
    @Override
    Object find(Type serviceType) throws ServiceLookupException;

    /**
     * Locates a factory which can create services of the given type.
     *
     * @param type The service type that the factory should create.
     * @param <T>  The service type that the factory should create.
     * @return The factory. Never returns null.
     * @throws UnknownServiceException When there is no factory available for services of the given type.
     * @throws ServiceLookupException On failure to lookup the specified service factory.
     */
    <T> Factory<T> getFactory(Class<T> type) throws UnknownServiceException, ServiceLookupException;

    /**
     * Creates a new service instance of the given type.
     *
     * @param type The service type
     * @param <T>  The service type.
     * @return The instance. Never returns null.
     * @throws UnknownServiceException When there is no factory available for services of the given type.
     * @throws ServiceLookupException On failure to lookup the specified service factory.
     */
    <T> T newInstance(Class<T> type) throws UnknownServiceException, ServiceLookupException;

    ServiceRegistry EMPTY = new ServiceRegistry() {
        @Override
        public <T> T get(Class<T> serviceType) throws UnknownServiceException, ServiceLookupException {
            throw emptyServiceRegistryException(serviceType);
        }

        @Override
        public <T> List<T> getAll(Class<T> serviceType) throws ServiceLookupException {
            return ImmutableList.of();
        }

        @Override
        public Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException {
            throw emptyServiceRegistryException(serviceType);
        }

        @Override
        public Object find(Type serviceType) throws ServiceLookupException {
            return null;
        }

        @Override
        public <T> Factory<T> getFactory(Class<T> type) throws UnknownServiceException, ServiceLookupException {
            throw emptyServiceRegistryException(type);
        }

        private UnknownServiceException emptyServiceRegistryException(Type type) {
            return new UnknownServiceException(type, "Nothing is available in the empty service registry.");
        }

        @Override
        public <T> T newInstance(Class<T> type) throws UnknownServiceException, ServiceLookupException {
            throw emptyServiceRegistryException(type);
        }

        @Override
        public Object get(Type serviceType, Class<? extends Annotation> annotatedWith) throws UnknownServiceException, ServiceLookupException {
            throw emptyServiceRegistryException(serviceType);
        }
    };
}