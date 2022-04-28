package com.tyron.builder.api.tasks;

import java.lang.annotation.*;

/**
 * <p>Attached to a task property to indicate that the property specifies some input value for the task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>This will cause the task to be considered out-of-date when the property has changed.
 * This annotation cannot be used on a {@link java.io.File} object. If you want to refer to the file path,
 * independently of its contents, return a {@link java.lang.String String} instead which returns the absolute
 * path.
 * If, instead, you want to refer to the contents and path of a file or a directory, use
 * {@link InputFile} or {@link InputDirectory} respectively.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Input {
}