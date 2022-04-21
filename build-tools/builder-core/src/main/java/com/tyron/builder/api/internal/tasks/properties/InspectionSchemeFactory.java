package com.tyron.builder.api.internal.tasks.properties;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.tyron.builder.internal.instantiation.InstantiationScheme;
import com.tyron.builder.internal.reflect.annotations.TypeAnnotationMetadataStore;
import com.tyron.builder.api.internal.tasks.properties.annotations.NoOpPropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import com.tyron.builder.api.internal.tasks.properties.annotations.TypeAnnotationHandler;
import com.tyron.builder.cache.internal.CrossBuildInMemoryCacheFactory;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class InspectionSchemeFactory {
    private final Map<Class<? extends Annotation>, PropertyAnnotationHandler> allKnownPropertyHandlers;
    private final ImmutableList<TypeAnnotationHandler> allKnownTypeHandlers;
    private final TypeAnnotationMetadataStore typeAnnotationMetadataStore;
    private final CrossBuildInMemoryCacheFactory cacheFactory;

    public InspectionSchemeFactory(
            List<? extends TypeAnnotationHandler> allKnownTypeHandlers,
            List<? extends PropertyAnnotationHandler> allKnownPropertyHandlers,
            TypeAnnotationMetadataStore typeAnnotationMetadataStore,
            CrossBuildInMemoryCacheFactory cacheFactory
    ) {
        ImmutableMap.Builder<Class<? extends Annotation>, PropertyAnnotationHandler> builder = ImmutableMap.builder();
        for (PropertyAnnotationHandler handler : allKnownPropertyHandlers) {
            builder.put(handler.getAnnotationType(), handler);
        }
        this.allKnownTypeHandlers = ImmutableList.copyOf(allKnownTypeHandlers);
        this.allKnownPropertyHandlers = builder.build();
        this.typeAnnotationMetadataStore = typeAnnotationMetadataStore;
        this.cacheFactory = cacheFactory;
    }

    /**
     * Creates a new {@link InspectionScheme} with the given annotations enabled and using the given instantiation scheme.
     */
    public InspectionScheme inspectionScheme(Collection<Class<? extends Annotation>> annotations, Collection<Class<? extends Annotation>> propertyModifiers, InstantiationScheme instantiationScheme) {
        ImmutableList.Builder<PropertyAnnotationHandler> propertyHandlers = ImmutableList.builderWithExpectedSize(annotations.size());
        for (Class<? extends Annotation> annotation : annotations) {
            PropertyAnnotationHandler propertyHandler = allKnownPropertyHandlers.get(annotation);
            if (propertyHandler == null) {
                throw new IllegalArgumentException(String.format("@%s is not a registered property type annotation.", annotation.getSimpleName()));
            }
            propertyHandlers.add(propertyHandler);
        }
        for (Class<? extends Annotation> annotation : instantiationScheme.getInjectionAnnotations()) {
            if (!annotations.contains(annotation)) {
                propertyHandlers.add(new NoOpPropertyAnnotationHandler(annotation));
            }
        }
        return new InspectionSchemeImpl(allKnownTypeHandlers, propertyHandlers.build(), propertyModifiers, typeAnnotationMetadataStore, cacheFactory);
    }

    private static class InspectionSchemeImpl implements InspectionScheme {
        private final DefaultPropertyWalker propertyWalker;
        private final DefaultTypeMetadataStore metadataStore;

        public InspectionSchemeImpl(List<TypeAnnotationHandler> typeHandlers, List<PropertyAnnotationHandler> propertyHandlers, Collection<Class<? extends Annotation>> propertyModifiers, TypeAnnotationMetadataStore typeAnnotationMetadataStore, CrossBuildInMemoryCacheFactory cacheFactory) {
            metadataStore = new DefaultTypeMetadataStore(typeHandlers, propertyHandlers, propertyModifiers, typeAnnotationMetadataStore, cacheFactory);
            propertyWalker = new DefaultPropertyWalker(metadataStore);
        }

        @Override
        public TypeMetadataStore getMetadataStore() {
            return metadataStore;
        }

        @Override
        public PropertyWalker getPropertyWalker() {
            return propertyWalker;
        }
    }
}