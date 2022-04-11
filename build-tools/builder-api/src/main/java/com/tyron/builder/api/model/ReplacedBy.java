package com.tyron.builder.api.model;

import com.tyron.builder.api.tasks.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>Attached to a task property to indicate that the property has been replaced by another. Like {@link Internal}, the property is ignored during up-to-date checks.</p>
 *
 * <p>This annotation should be attached to the getter method in Java or the property field in Groovy. You should also consider adding {@link Deprecated} to any replaced property.</p>
 *
 * <p>This will cause the task <em>not</em> to be considered out-of-date when the property has changed.</p>
 *
 * @since 5.4
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface ReplacedBy {
    /**
     * The Java Bean-style name of the replacement property.
     * <p>
     *     If the property has been replaced with a method named {@code getFooBar()}, then this should be {@code fooBar}.
     * </p>
     */
    String value();
}