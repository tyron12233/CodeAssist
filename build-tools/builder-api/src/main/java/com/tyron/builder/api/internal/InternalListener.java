package com.tyron.builder.api.internal;

/**
 * Indicator that the object should not be considered user code.
 * <p>
 * Execution of user code listeners are observable as build operations, with provenance information.
 * Internal listeners are not.
 * This is implemented by decorating user code listeners at the registration site.
 * This interface is used to suppress the decoration.
 * <p>
 * User can generally do very little about internal listeners (i.e. they are a fixed cost),
 * while they do have control of user code listeners.
 * <p>
 * There are some reusable implementations of this, such as {@link org.gradle.api.internal.InternalAction}.
 */
public interface InternalListener {
}