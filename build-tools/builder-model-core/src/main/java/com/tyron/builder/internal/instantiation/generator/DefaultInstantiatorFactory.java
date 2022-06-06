package com.tyron.builder.internal.instantiation.generator;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;
import com.tyron.builder.internal.instantiation.InjectAnnotationHandler;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.instantiation.ManagedTypeFactory;
import com.tyron.builder.internal.instantiation.PropertyRoleAnnotationHandler;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceLookup;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.state.ManagedFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class DefaultInstantiatorFactory implements InstantiatorFactory {
    private static final int MANAGED_FACTORY_ID = Objects.hashCode(ClassGeneratorBackedManagedFactory.class.getName());

    private final ServiceRegistry defaultServices;
    private final CrossBuildInMemoryCacheFactory cacheFactory;
    private final List<InjectAnnotationHandler> annotationHandlers;
    private final PropertyRoleAnnotationHandler roleHandler;
    private final DefaultInstantiationScheme injectOnlyScheme;
    private final DefaultInstantiationScheme injectOnlyLenientScheme;
    private final DefaultInstantiationScheme decoratingScheme;
    private final DefaultInstantiationScheme decoratingLenientScheme;
    private final ManagedFactory managedFactory;

    public DefaultInstantiatorFactory(CrossBuildInMemoryCacheFactory cacheFactory, List<InjectAnnotationHandler> injectHandlers, PropertyRoleAnnotationHandler roleAnnotationHandler) {
        this.cacheFactory = cacheFactory;
        this.annotationHandlers = injectHandlers;
        this.roleHandler = roleAnnotationHandler;
        DefaultServiceRegistry services = new DefaultServiceRegistry("default services");
        services.add(InstantiatorFactory.class, this);
        this.defaultServices = services;
        ClassGenerator injectOnlyGenerator = AsmBackedClassGenerator.injectOnly(injectHandlers, roleAnnotationHandler, ImmutableSet
                .of(), cacheFactory, MANAGED_FACTORY_ID);
        ClassGenerator decoratedGenerator = AsmBackedClassGenerator.decorateAndInject(injectHandlers, roleAnnotationHandler, ImmutableSet.of(), cacheFactory, MANAGED_FACTORY_ID);
        this.managedFactory = new ClassGeneratorBackedManagedFactory(injectOnlyGenerator);
        ConstructorSelector injectOnlyJsr330Selector = new Jsr330ConstructorSelector(injectOnlyGenerator, cacheFactory.newClassCache());
        ConstructorSelector decoratedJsr330Selector = new Jsr330ConstructorSelector(decoratedGenerator, cacheFactory.newClassCache());
        ConstructorSelector injectOnlyLenientSelector = new ParamsMatchingConstructorSelector(injectOnlyGenerator);
        ConstructorSelector decoratedLenientSelector = new ParamsMatchingConstructorSelector(decoratedGenerator);
        injectOnlyScheme = new DefaultInstantiationScheme(injectOnlyJsr330Selector, injectOnlyGenerator, defaultServices, ImmutableSet.of(Inject.class), cacheFactory);
        injectOnlyLenientScheme = new DefaultInstantiationScheme(injectOnlyLenientSelector, injectOnlyGenerator, defaultServices, ImmutableSet.of(Inject.class), cacheFactory);
        decoratingScheme = new DefaultInstantiationScheme(decoratedJsr330Selector, decoratedGenerator, defaultServices, ImmutableSet.of(Inject.class), cacheFactory);
        decoratingLenientScheme = new DefaultInstantiationScheme(decoratedLenientSelector, decoratedGenerator, defaultServices, ImmutableSet.of(Inject.class), cacheFactory);
    }

    @Override
    public InstanceGenerator inject(ServiceLookup services) {
        return injectOnlyScheme.withServices(services).instantiator();
    }

    @Override
    public InstanceGenerator inject() {
        return injectOnlyScheme.instantiator();
    }

    @Override
    public InstantiationScheme injectScheme() {
        return injectOnlyScheme;
    }

    @Override
    public InstantiationScheme injectScheme(Collection<Class<? extends Annotation>> injectAnnotations) {
        if (injectAnnotations.isEmpty()) {
            return injectOnlyScheme;
        }

        for (Class<? extends Annotation> annotation : injectAnnotations) {
            assertKnownAnnotation(annotation);
        }

        ClassGenerator classGenerator = AsmBackedClassGenerator.injectOnly(annotationHandlers, roleHandler, ImmutableSet.copyOf(injectAnnotations), cacheFactory, MANAGED_FACTORY_ID);
        Jsr330ConstructorSelector constructorSelector = new Jsr330ConstructorSelector(classGenerator, cacheFactory.newClassCache());
        ImmutableSet.Builder<Class<? extends Annotation>> builder = ImmutableSet.builderWithExpectedSize(injectAnnotations.size() + 1);
        builder.addAll(injectAnnotations);
        builder.add(Inject.class);
        return new DefaultInstantiationScheme(constructorSelector, classGenerator, defaultServices, builder.build(), cacheFactory);
    }

    @Override
    public InstanceGenerator injectLenient() {
        return injectOnlyLenientScheme.instantiator();
    }

    @Override
    public InstanceGenerator injectLenient(ServiceLookup services) {
        return injectOnlyLenientScheme.withServices(services).instantiator();
    }

    @Override
    public InstantiationScheme decorateLenientScheme() {
        return decoratingLenientScheme;
    }

    @Override
    public InstanceGenerator decorateLenient() {
        return decoratingLenientScheme.instantiator();
    }

    @Override
    public InstanceGenerator decorateLenient(ServiceLookup services) {
        return decoratingLenientScheme.withServices(services).instantiator();
    }

    @Override
    public InstantiationScheme decorateScheme() {
        return decoratingScheme;
    }

    @Override
    public InstanceGenerator decorate(ServiceLookup services) {
        return decoratingScheme.withServices(services).instantiator();
    }

    @Override
    public ManagedFactory getManagedFactory() {
        return managedFactory;
    }

    private void assertKnownAnnotation(Class<? extends Annotation> annotation) {
        for (InjectAnnotationHandler annotationHandler : annotationHandlers) {
            if (annotationHandler.getAnnotationType().equals(annotation)) {
                return;
            }
        }
        throw new IllegalArgumentException(String.format("Annotation @%s is not a registered injection annotation.", annotation.getSimpleName()));
    }

    private static class ClassGeneratorBackedManagedFactory implements ManagedFactory {
        private final ClassGenerator classGenerator;

        public ClassGeneratorBackedManagedFactory(ClassGenerator classGenerator) {
            this.classGenerator = classGenerator;
        }

        @Nullable
        @Override
        public <T> T fromState(Class<T> type, Object state) {
            Class<?> generatedClass = classGenerator.generate(type).getGeneratedClass();
            return new ManagedTypeFactory(generatedClass).fromState(type, state);
        }

        @Override
        public int getId() {
            return MANAGED_FACTORY_ID;
        }
    }
}
