package org.gradle.api.internal.tasks.properties.annotations;

import static org.gradle.api.internal.tasks.properties.annotations.TypeAnnotationHandlerSupport.reportInvalidUseOfTypeAnnotation;

import org.gradle.api.Task;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.tasks.CacheableTask;

import java.lang.annotation.Annotation;

public class CacheableTaskTypeAnnotationHandler implements TypeAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return CacheableTask.class;
    }

    @Override
    public void validateTypeMetadata(Class<?> classWithAnnotationAttached, TypeValidationContext visitor) {
        if (!Task.class.isAssignableFrom(classWithAnnotationAttached)) {
            reportInvalidUseOfTypeAnnotation(classWithAnnotationAttached, visitor, getAnnotationType(), Task.class);
        }
    }

}