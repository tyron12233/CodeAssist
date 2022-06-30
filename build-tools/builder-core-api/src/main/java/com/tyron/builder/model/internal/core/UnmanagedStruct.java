package com.tyron.builder.model.internal.core;

import java.lang.annotation.*;

/**
 * Indicates that the type that this annotation is attached to represents some struct type whose lifecycle and implementation
 * is not managed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface UnmanagedStruct {
}
