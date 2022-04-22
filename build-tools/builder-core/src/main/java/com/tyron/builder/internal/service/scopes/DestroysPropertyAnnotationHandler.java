package com.tyron.builder.internal.service.scopes;

import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.OPTIONAL;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory;
import com.tyron.builder.internal.reflect.AnnotationCategory;
import com.tyron.builder.internal.reflect.PropertyMetadata;
import com.tyron.builder.api.internal.tasks.properties.BeanPropertyContext;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;
import com.tyron.builder.api.tasks.Destroys;

import java.lang.annotation.Annotation;


public class DestroysPropertyAnnotationHandler implements PropertyAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return Destroys.class;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(ModifierAnnotationCategory.OPTIONAL);
    }

    @Override
    public boolean isPropertyRelevant() {
        return true;
    }

    @Override
    public boolean shouldVisit(PropertyVisitor visitor) {
        return true;
    }

    @Override
    public void visitPropertyValue(String propertyName, PropertyValue value, PropertyMetadata propertyMetadata, PropertyVisitor visitor, BeanPropertyContext context) {
        visitor.visitDestroyableProperty(value);
    }
}