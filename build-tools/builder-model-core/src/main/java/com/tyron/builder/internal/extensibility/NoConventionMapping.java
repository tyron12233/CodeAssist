package com.tyron.builder.internal.extensibility;

import com.tyron.builder.api.internal.IConventionAware;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Disables the application of convention mapping for the class it is attached to, and all superclasses.
 *
 * @see IConventionAware
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoConventionMapping {
}
