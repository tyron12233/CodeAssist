package com.tyron.builder.api.tasks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Marks a task property as optional. This means that a value does not have to be specified for the property, but any
 * value specified must meet the validation constraints for the property.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property in Groovy.
 * Annotations on setters or just the field in Java are ignored.</p>
 *
 * <ul> <li>{@link Input}</li>
 *
 * <li>{@link InputFile}</li>
 *
 * <li>{@link InputDirectory}</li>
 *
 * <li>{@link InputFiles}</li>
 *
 * <li>{@link oOutputFile}</li>
 *
 * <li>{@link OutputDirectory}</li> </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface Optional {
}