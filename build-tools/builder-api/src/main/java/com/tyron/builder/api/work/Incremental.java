package com.tyron.builder.api.work;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Track input changes for the annotated parameter.
 *
 * <p>
 *     Inputs annotated with {@link Incremental} can be queried for changes via {@link InputChanges#getFileChanges(org.gradle.api.file.FileCollection)} or {@link org.gradle.work.InputChanges#getFileChanges(org.gradle.api.provider.Provider)}.
 * </p>
 *
 * @since 5.4
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
@Documented
public @interface Incremental {
}