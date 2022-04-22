package com.tyron.builder.api.internal.tasks.properties.annotations;

import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.OPTIONAL;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.reflect.AnnotationCategory;
import com.tyron.builder.internal.reflect.PropertyMetadata;
import com.tyron.builder.api.internal.tasks.properties.BeanPropertyContext;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.tasks.Optional;

public abstract class AbstractOutputPropertyAnnotationHandler implements PropertyAnnotationHandler {
    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(OPTIONAL);
    }

    protected abstract OutputFilePropertyType getFilePropertyType();

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
        visitor.visitOutputFileProperty(propertyName, propertyMetadata.isAnnotationPresent(Optional.class), value, getFilePropertyType());
    }
}