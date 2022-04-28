package com.tyron.builder.api.internal.tasks.properties.annotations;

import static com.tyron.builder.api.internal.tasks.properties.annotations.TypeAnnotationHandlerSupport.reportInvalidUseOfTypeAnnotation;

import com.tyron.builder.api.Task;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.tasks.CacheableTask;

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