package com.tyron.builder.api.internal.reflect.service;

import java.lang.annotation.Annotation;

/**
 * Service instances can implement this interface to apply some lifecycle to all services annotation with a given annotation.
 */
public interface AnnotatedServiceLifecycleHandler {
    Class<? extends Annotation> getAnnotation();

    /**
     * Called when a service with the given annotation is registered.
     */
    void whenRegistered(Registration registration);

    interface Registration {
        Class<?> getDeclaredType();

        /**
         * Returns the service instance, creating it if required.
         */
        Object getInstance();
    }
}