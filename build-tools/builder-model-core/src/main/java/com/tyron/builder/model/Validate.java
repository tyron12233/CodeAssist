package com.tyron.builder.model;

import com.tyron.builder.api.Incubating;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the {@link RuleSource} method rule carrying this annotation validates the rule subject.
 * <p>
 * Validate rules execute after {@link Finalize} rules, but before rule subject is used as an input.
 * The first parameter of the rule is the rule subject, which is <b>immutable</b>.
 * <p>
 * Please see {@link RuleSource} for more information on method rules.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Incubating
public @interface Validate {
}
