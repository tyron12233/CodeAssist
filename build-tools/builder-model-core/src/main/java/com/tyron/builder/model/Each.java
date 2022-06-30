package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Signals that a {@link RuleSource} rule should be applied to all matching descendant elements of the scope instead of the scope itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Incubating
public @interface Each {
}
