package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the {@link RuleSource} method rule carrying this annotation finalizes the rule subject.
 * <p>
 * Finalize rules execute after {@link Mutate} rules, but before {@link Validate} rules.
 * The first parameter of the rule is the rule subject, which is mutable for the duration of the rule.
 * <p>
 * Please see {@link RuleSource} for more information on method rules.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Incubating
public @interface Finalize {
}
