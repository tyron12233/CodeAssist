package com.tyron.builder.model.internal.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applied together with {@link om.tyron.builder.model.Model} annotation the registered model element
 * will not be shown in reports.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Hidden {
}
