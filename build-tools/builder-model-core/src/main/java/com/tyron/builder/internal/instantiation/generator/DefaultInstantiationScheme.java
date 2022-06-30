package com.tyron.builder.internal.instantiation.generator;

import com.tyron.builder.internal.instantiation.DeserializationInstantiator;
import com.tyron.builder.internal.instantiation.InstanceFactory;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.service.ServiceLookup;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCache;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

public class DefaultInstantiationScheme implements InstantiationScheme {
    private final DependencyInjectingInstantiator instantiator;
    private final ConstructorSelector constructorSelector;
    private final Set<Class<? extends Annotation>> injectionAnnotations;
    private final CrossBuildInMemoryCache<Class<?>, ClassGenerator.SerializationConstructor<?>>
            deserializationConstructorCache;
    private final DeserializationInstantiator deserializationInstantiator;
    private final ClassGenerator classGenerator;

    public DefaultInstantiationScheme(ConstructorSelector constructorSelector,
                                      ClassGenerator classGenerator,
                                      ServiceLookup defaultServices,
                                      Set<Class<? extends Annotation>> injectionAnnotations,
                                      CrossBuildInMemoryCacheFactory cacheFactory) {
        this(constructorSelector, classGenerator, defaultServices, injectionAnnotations,
                cacheFactory.newClassCache());
    }

    private DefaultInstantiationScheme(ConstructorSelector constructorSelector,
                                       ClassGenerator classGenerator,
                                       ServiceLookup defaultServices,
                                       Set<Class<? extends Annotation>> injectionAnnotations,
                                       CrossBuildInMemoryCache<Class<?>, ClassGenerator.SerializationConstructor<?>> deserializationConstructorCache) {
        this.classGenerator = classGenerator;
        this.instantiator = new DependencyInjectingInstantiator(constructorSelector, defaultServices);
        this.constructorSelector = constructorSelector;
        this.injectionAnnotations = injectionAnnotations;
        this.deserializationConstructorCache = deserializationConstructorCache;
        this.deserializationInstantiator =
                new DefaultDeserializationInstantiator(classGenerator, defaultServices, instantiator, deserializationConstructorCache);
    }

    @Override
    public Set<Class<? extends Annotation>> getInjectionAnnotations() {
        return injectionAnnotations;
    }

    @Override
    public <T> InstanceFactory<T> forType(Class<T> type) {
        return instantiator.factoryFor(type);
    }

    @Override
    public InstantiationScheme withServices(ServiceLookup services) {
        return new DefaultInstantiationScheme(constructorSelector, classGenerator, services,
                injectionAnnotations, deserializationConstructorCache);
    }

    @Override
    public InstanceGenerator instantiator() {
        return instantiator;
    }

    @Override
    public DeserializationInstantiator deserializationInstantiator() {
        return deserializationInstantiator;
    }

    private static class DefaultDeserializationInstantiator implements DeserializationInstantiator {
        private final ClassGenerator classGenerator;
        private final ServiceLookup services;
        private final InstanceGenerator nestedGenerator;
        private final CrossBuildInMemoryCache<Class<?>, ClassGenerator.SerializationConstructor<?>>
                constructorCache;

        public DefaultDeserializationInstantiator(ClassGenerator classGenerator,
                                                  ServiceLookup services,
                                                  InstanceGenerator nestedGenerator,
                                                  CrossBuildInMemoryCache<Class<?>, ClassGenerator.SerializationConstructor<?>> constructorCache) {
            this.classGenerator = classGenerator;
            this.services = services;
            this.nestedGenerator = nestedGenerator;
            this.constructorCache = constructorCache;
        }

        @Override
        public <T> T newInstance(Class<T> implType, Class<? super T> baseClass) {
            // TODO - The baseClass can be inferred from the implType, so attach the serialization constructor onto the GeneratedClass rather than parameterizing and caching here
            try {
                ClassGenerator.SerializationConstructor<?> constructor = constructorCache
                        .get(implType, () -> classGenerator.generate(implType).getSerializationConstructor(baseClass));
                return implType.cast(constructor.newInstance(services, nestedGenerator));
            } catch (InvocationTargetException e) {
                throw new ObjectInstantiationException(implType, e.getCause());
            } catch (Exception e) {
                throw new ObjectInstantiationException(implType, e);
            }
        }
    }
}