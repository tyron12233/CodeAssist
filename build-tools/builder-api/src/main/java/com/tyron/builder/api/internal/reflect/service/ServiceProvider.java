package com.tyron.builder.api.internal.reflect.service;

import com.tyron.builder.api.internal.concurrent.Stoppable;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;

/**
 * Provides a set of zero or more services. The get-methods may be called concurrently. {@link #stop()} is guaranteed to be only called once,
 * after all get-methods have completed.
 */
interface ServiceProvider extends Stoppable {
    /**
     * Locates a service instance of the given type. Returns null if this provider does not provide a service of this type.
     */
    @Nullable
    Service getService(Type serviceType);

    /**
     * Locates a factory for services of the given type. Returns null if this provider does not provide any services of this type.
     */
    @Nullable Service getFactory(Class<?> type);

    /**
     * Collects all services of the given type.
     *
     * @return A visitor that should be used for all subsequent services.
     */
    Visitor getAll(Class<?> serviceType, Visitor visitor);

    interface Visitor {
        void visit(Service service);
    }
}