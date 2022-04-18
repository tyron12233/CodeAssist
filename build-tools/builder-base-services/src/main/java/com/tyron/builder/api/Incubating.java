package com.tyron.builder.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a feature is incubating. This means that the feature is currently a work-in-progress and may
 * change at any time.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.PACKAGE,
        ElementType.TYPE,
        ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.METHOD
})
public @interface Incubating {
}
