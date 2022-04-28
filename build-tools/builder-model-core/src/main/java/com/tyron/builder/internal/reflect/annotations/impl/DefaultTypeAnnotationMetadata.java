package com.tyron.builder.internal.reflect.annotations.impl;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.tyron.builder.internal.reflect.annotations.PropertyAnnotationMetadata;
import com.tyron.builder.internal.reflect.annotations.TypeAnnotationMetadata;
import com.tyron.builder.internal.reflect.validation.ReplayingTypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.lang.annotation.Annotation;

public class DefaultTypeAnnotationMetadata implements TypeAnnotationMetadata {
    private final ImmutableBiMap<Class<? extends Annotation>, Annotation> annotations;
    private final ImmutableSortedSet<PropertyAnnotationMetadata> properties;
    private final ReplayingTypeValidationContext validationProblems;

    public DefaultTypeAnnotationMetadata(Iterable<? extends Annotation> annotations, Iterable<? extends PropertyAnnotationMetadata> properties, ReplayingTypeValidationContext validationProblems) {
        this.annotations = ImmutableBiMap.copyOf(Maps.uniqueIndex(annotations, Annotation::annotationType));
        this.properties = ImmutableSortedSet.copyOf(properties);
        this.validationProblems = validationProblems;
    }

    @Override
    public ImmutableSet<Annotation> getAnnotations() {
        return annotations.values();
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return annotations.containsKey(annotationType);
    }

    @Override
    public ImmutableSortedSet<PropertyAnnotationMetadata> getPropertiesAnnotationMetadata() {
        return properties;
    }

    @Override
    public void visitValidationFailures(TypeValidationContext validationContext) {
        validationProblems.replay(null, validationContext);
    }
}