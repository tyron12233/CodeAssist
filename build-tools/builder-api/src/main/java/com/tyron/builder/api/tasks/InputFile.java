package com.tyron.builder.api.tasks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying an input file for a task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>This will cause the task to be considered out-of-date when the file path or contents
 * have changed.</p>
 *
 * <p><strong>Note:</strong> To make the task dependent on the file's location but not its
 * contents, expose the path of the file as an {@link Input} property instead.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface InputFile {
}