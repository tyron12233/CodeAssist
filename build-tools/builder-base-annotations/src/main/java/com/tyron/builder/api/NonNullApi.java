package com.tyron.builder.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type or a whole package as providing a non-null API by default.
 *
 * All parameter and return types are assumed to be {@link Nonnull} unless specifically marked as {@link Nullable}.
 *
 * All types of an annotated package inherit the package rule.
 * Subpackages do not inherit nullability rules and must be annotated.
 *
 * @since 4.2
 */
@Target({ElementType.TYPE, ElementType.PACKAGE})
//@Nonnull
//@TypeQualifierDefault({ElementType.METHOD, ElementType.PARAMETER})
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface NonNullApi {
}
