package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a property of a managed model element is explicitly of an unmanaged type.
 * <p>
 * This annotation must be present on the <b>getter</b> of the property for the unmanaged type.
 * If the annotation is not present for a property that is not a managed type, a fatal error will occur.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Incubating
public @interface Unmanaged {
    // Note: this may be a temporary measure while existing infrastructure is being ported to managed model elements
}
