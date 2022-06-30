package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a task file property, specifying which part of the file paths should be considered during up-to-date checks.
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>If a {@link Task} declares a file property without this annotation, the default is {@link PathSensitivity#ABSOLUTE}.</p>
 *
 * @since 3.1
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface PathSensitive {
    PathSensitivity value();
}