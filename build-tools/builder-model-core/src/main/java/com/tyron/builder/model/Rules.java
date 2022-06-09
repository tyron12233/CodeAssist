package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the {@link RuleSource} method rule carrying this annotation defines additional rules based on its inputs.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Incubating
public @interface Rules {
}
