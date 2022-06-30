package com.tyron.builder.internal.exceptions;

import java.lang.annotation.*;

/**
 * This annotation is attached to an exception class to indicate that it provides contextual information about the
 * exception which might help the user determine what the failed operation was, or where it took place. Generally, this
 * annotation is only attached to exceptions which chain lower-level exceptions.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface Contextual {
}