package com.tyron.builder.internal.scan;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents that the type or method is referenced by the build scan plugin,
 * and therefore changes need to be carefully managed. Other plugins like the
 * test-retry or the test-distribution plugin clarify their usage in the {@link #value}.
 * property.
 *
 * @since 4.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface UsedByScanPlugin {

    /**
     * Any clarifying comments about how it is used.
     */
    String value() default "";

}
