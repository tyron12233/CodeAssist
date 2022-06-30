package com.tyron.builder.api.reflect;

/**
 * Allows a scriptable object, such as a project extension, to declare its preferred public type.
 *
 * The public type of an object is the one exposed to statically-typed consumers, such as Kotlin build scripts, by default.
 *
 * @since 3.5
 */
public interface HasPublicType {

    /**
     * Public type.
     *
     * @return this object's public type
     */
    TypeOf<?> getPublicType();
}
