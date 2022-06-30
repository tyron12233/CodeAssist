package com.tyron.builder.util;

import groovy.lang.Closure;

/**
 * An object that can be configured with a Groovy closure.
 *
 * @param <T> the closure return type.
 */
public interface Configurable<T> {
    T configure(Closure cl);
}
