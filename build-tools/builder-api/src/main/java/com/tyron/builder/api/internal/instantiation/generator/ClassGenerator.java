package com.tyron.builder.api.internal.instantiation.generator;


import com.tyron.builder.api.Describable;
import com.tyron.builder.api.internal.instantiation.ClassGenerationException;
import com.tyron.builder.api.internal.instantiation.InstanceGenerator;
import com.tyron.builder.api.internal.reflect.service.ServiceLookup;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.List;

interface ClassGenerator {
    /**
     * Generates a proxy class for the given class. May return the given class unmodified or may generate a subclass.
     *
     * <p>Implementation should ensure that it is efficient to call this method multiple types for the same class.</p>
     */
    <T> GeneratedClass<? extends T> generate(Class<T> type) throws ClassGenerationException;

    interface GeneratedClass<T> {
        Class<T> getGeneratedClass();

        /**
         * Returns the enclosing type, when this type is a non-static inner class.
         */
        @Nullable
        Class<?> getOuterType();

        List<GeneratedConstructor<T>> getConstructors();

        /**
         * Creates a serialization constructor. Note: this can be expensive and does not perform any caching.
         */
        SerializationConstructor<T> getSerializationConstructor(Class<? super T> baseClass);
    }

    interface SerializationConstructor<T> {
        /**
         * Creates a new instance, using the given services and parameters. Uses the given instantiator to create nested objects, if required.
         */
        T newInstance(ServiceLookup services, InstanceGenerator nested) throws InvocationTargetException, IllegalAccessException, InstantiationException;
    }

    interface GeneratedConstructor<T> {
        /**
         * Creates a new instance, using the given services and parameters. Uses the given instantiator to create nested objects, if required.
         */
        T newInstance(ServiceLookup services, InstanceGenerator nested, @Nullable Describable displayName, Object[] params) throws InvocationTargetException, IllegalAccessException, InstantiationException;

        /**
         * Does this constructor use the given service type?
         */
        boolean requiresService(Class<?> serviceType);

        /**
         * Does this constructor use a service injected via the given annotation?
         */
        boolean serviceInjectionTriggeredByAnnotation(Class<? extends Annotation> serviceAnnotation);

        Class<?>[] getParameterTypes();

        Type[] getGenericParameterTypes();

        @Nullable
        <S extends Annotation> S getAnnotation(Class<S> annotation);

        int getModifiers();
    }

}
