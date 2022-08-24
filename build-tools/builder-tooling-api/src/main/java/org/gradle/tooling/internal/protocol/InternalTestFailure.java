package org.gradle.tooling.internal.protocol;

import javax.annotation.Nullable;

/**
 * DO NOT CHANGE THIS INTERFACE. It is part of the cross-version protocol.
 *
 * Describes a test failure, that can either be a test assertion failure or a test framework failure.
 *
 * @since 7.6
 * @see InternalTestAssertionFailure
 * @see InternalTestFrameworkFailure
 */
public interface InternalTestFailure extends InternalFailure {

    /**
     * The message associated with the failure. Usually (but not always) equals to the message in the underlying exception's message.
     *
     * @return The failure message
     */
    @Nullable
    String getMessage();

    /**
     * The fully-qualified name of the underlying exception type.
     *
     * @return The exception class name
     */
    String getClassName();

    /**
     * The stringified version of the stacktrace created from the underlying exception.
     *
     * @return the stacktrace
     */
    String getStacktrace();
}