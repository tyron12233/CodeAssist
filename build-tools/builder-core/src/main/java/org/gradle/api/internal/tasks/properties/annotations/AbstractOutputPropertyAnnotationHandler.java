package org.gradle.api.internal.tasks.properties.annotations;

import static org.gradle.api.internal.tasks.properties.ModifierAnnotationCategory.OPTIONAL;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.reflect.AnnotationCategory;
import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.api.internal.tasks.properties.BeanPropertyContext;
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.tasks.Optional;

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