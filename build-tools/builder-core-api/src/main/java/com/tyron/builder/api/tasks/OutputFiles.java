package com.tyron.builder.api.tasks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying one or more output files for a task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>This will cause the task to be considered out-of-date when the file paths or contents
 * are different to when the task was last run.</p>
 *
 * <p>When the annotated property is a {@link java.util.Map}, then the keys of the map must be non-empty strings.
 * The values of the map will be evaluated to individual files as per
 * {@link com.tyron.builder.api.project.BuildProject#file(Object)}.</p>
 *
 * <p>
 * Otherwise the given files will be evaluated as per {@link com.tyron.builder.api.project.BuildProject#files(Object...)}.
 * Task output caching will be disabled if the outputs contain file trees.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface OutputFiles {
}