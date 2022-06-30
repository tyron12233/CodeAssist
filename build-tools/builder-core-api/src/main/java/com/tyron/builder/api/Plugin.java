package com.tyron.builder.api;

/**
 * <p>A <code>Plugin</code> represents an extension to Gradle. A plugin applies some configuration to a target object.
 * Usually, this target object is a {@link BuildProject}, but plugins can be applied to any type of
 * objects.</p>
 *
 * @param <T> The type of object which this plugin can configure.
 */
public interface Plugin<T> {
    /**
     * Apply this plugin to the given target object.
     *
     * @param target The target object
     */
    void apply(T target);
}
