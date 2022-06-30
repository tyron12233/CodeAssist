package com.tyron.builder.api.tasks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a property as specifying a file or directory that a task destroys.</p>
 *
 * <p>This annotation should be attached to the getter method or the field for the property.</p>
 *
 * <p>This will cause the task to have exclusive access to this file or directory while running.  This means
 * that other tasks that either create or consume this file (by specifying the file or directory as an input
 * or output) cannot execute concurrently with a task that destroys this file.</p>
 *
 * @since 4.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Destroys {
}