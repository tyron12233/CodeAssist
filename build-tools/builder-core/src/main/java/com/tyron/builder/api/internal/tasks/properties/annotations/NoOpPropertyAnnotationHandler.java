package com.tyron.builder.api.internal.tasks.properties.annotations;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.reflect.AnnotationCategory;
import com.tyron.builder.internal.reflect.PropertyMetadata;
import com.tyron.builder.api.internal.tasks.properties.BeanPropertyContext;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;

import java.lang.annotation.Annotation;

public class NoOpPropertyAnnotationHandler implements PropertyAnnotationHandler {
    private final Class<? extends Annotation> annotationType;

    public NoOpPropertyAnnotationHandler(Class<? extends Annotation> annotationType) {
        this.annotationType = annotationType;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of();
    }

    @Override
    public boolean isPropertyRelevant() {
        return false;
    }

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    @Override
    public boolean shouldVisit(PropertyVisitor visitor) {
        return false;
    }

    @Override
    public void visitPropertyValue(String propertyName, PropertyValue value, PropertyMetadata propertyMetadata, PropertyVisitor visitor, BeanPropertyContext context) {
    }
}
