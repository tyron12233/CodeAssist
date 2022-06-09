package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the {@link RuleSource} method rule carrying this annotation mutates the rule subject.
 * <p>
 * Mutate rules execute after {@link Defaults} rules, but before {@link Finalize} rules.
 * The first parameter of the rule is the rule subject, which is mutable for the duration of the rule.
 * <p>
 * Please see {@link RuleSource} for more information on method rules.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Incubating
public @interface Mutate {
}
