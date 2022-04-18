package com.tyron.builder.api.tasks;


import java.lang.annotation.*;

/**
 * <p>Marks a property as specifying an output file for a task.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <p>This will cause the task to be considered out-of-date when the file path or contents
 * are different to when the task was last run.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface OutputFile {
}