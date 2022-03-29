package com.tyron.builder.api.internal.reflect;

import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public interface PropertyMetadata {
    String getPropertyName();

    boolean isAnnotationPresent(Class<? extends Annotation> annotationType);

    @Nullable
    Annotation getAnnotationForCategory(AnnotationCategory category);

    boolean hasAnnotationForCategory(AnnotationCategory category);

    Class<? extends Annotation> getPropertyType();

    Method getGetterMethod();
}
