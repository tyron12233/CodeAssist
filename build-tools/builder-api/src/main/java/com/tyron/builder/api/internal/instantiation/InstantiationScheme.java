package com.tyron.builder.api.internal.instantiation;

import com.tyron.builder.api.internal.reflect.service.ServiceLookup;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * A scheme, or strategy, for creating objects.
 *
 * <p>Implementations are provided by a {@link InstantiatorFactory}.</p>
 */
public interface InstantiationScheme {
    /**
     * Returns the set of annotations that are supported by this instantiation scheme for dependency injection.
     */
    Set<Class<? extends Annotation>> getInjectionAnnotations();

    /**
     * Creates a new {@link InstanceFactory} for the given type, which creates instances based on the configuration of this scheme.
     */
    <T> InstanceFactory<T> forType(Class<T> type);

    /**
     * Creates a new {@link InstantiationScheme} which creates instances using the given services, based on the configuration of this scheme.
     */
    InstantiationScheme withServices(ServiceLookup services);

    /**
     * Returns the instantiator which creates instances using a default set of services, based on the configuration of this scheme.
     */
    InstanceGenerator instantiator();

    /**
     * Returns an instantiator that creates instances to be deserialized, based on the configuration of this scheme.
     */
    DeserializationInstantiator deserializationInstantiator();
}
