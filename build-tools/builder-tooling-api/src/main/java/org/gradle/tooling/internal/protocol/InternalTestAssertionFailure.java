package org.gradle.tooling.internal.protocol;

import javax.annotation.Nullable;

/**
 * DO NOT CHANGE THIS INTERFACE. It is part of the cross-version protocol.
 *
 * Represent a test assertion failure where the test fails due to a broken assertion.
 *
 * @since 7.6
 */
public interface InternalTestAssertionFailure extends InternalTestFailure {

    /**
     * Returns the string representation of the expected value.
     *
     * @return the expected value or {@code null} if the test framework doesn't supply detailed information on assertion failures
     */
    @Nullable
    String getExpected();

    /**
     * Returns the string representation of the actual value.
     *
     * @return the actual value or {@code null} if the test framework doesn't supply detailed information on assertion failures
     */
    @Nullable
    String getActual();
}