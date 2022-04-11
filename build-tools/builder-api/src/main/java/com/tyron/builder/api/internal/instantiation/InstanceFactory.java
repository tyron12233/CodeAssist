package com.tyron.builder.api.internal.instantiation;

import com.tyron.builder.api.internal.reflect.service.ServiceLookup;

import java.lang.annotation.Annotation;

/**
 * Creates instances of the given type. This is similar to {@link org.gradle.internal.reflect.Instantiator}, but produces instances of the given type only. This allows it to provides some
 * additional metadata about the type, such as which services it requires, and may be faster at creating instances due to the extra context it holds.
 */
public interface InstanceFactory<T> {
    /**
     * Is the given service required to be injected by type?
     */
    boolean requiresService(Class<?> serviceType);

    /**
     * Is any service injection triggered by the given annotation?
     */
    boolean serviceInjectionTriggeredByAnnotation(Class<? extends Annotation> injectAnnotation);

    /**
     * Creates a new instance from the given services and parameters.
     */
    T newInstance(ServiceLookup services, Object... params);

    /**
     * Creates a new instance from the given parameters and the default services.
     */
    T newInstance(Object... params);
}
