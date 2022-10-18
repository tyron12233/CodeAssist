package org.gradle.api.tasks.testing;

import org.gradle.api.Incubating;

import javax.annotation.Nullable;

/**
 * Contains serializable structural information about a test failure.
 *
 * @since 7.6
 */
@Incubating
public interface TestFailureDetails {

    /**
     * Returns the failure message.
     *
     * @return the failure message
     */
    @Nullable
    String getMessage();

    /**
     * The fully-qualified name of the underlying exception type.
     *
     * @return the class name
     */
    String getClassName();

    /**
     * Returns the stacktrace of the failure.
     * <p>
     * The instances are created on the test worker side allowing the clients not to deal with non-serializable exceptions.
     *
     * @return the stacktrace string
     */
    String getStacktrace();

    /**
     * Returns true if the represented failure is recognized as an assertion failure.
     *
     * @return {@code true} for assertion failures
     */
    boolean isAssertionFailure();

    /**
     * Returns a string representation of the expected value for an assertion failure.
     * <p>
     * If the current instance does not represent an assertion failure, or the test failure doesn't provide any information about expected and actual values then the method returns {@code null}.
     *
     * @return The expected value
     */
    @Nullable
    String getExpected();

    /**
     * Returns a string representation of the actual value for an assertion failure.
     * <p>
     * If the current instance does not represent an assertion failure, or the test failure doesn't provide any information about expected and actual values then the method returns {@code null}.
     *
     * @return The actual value value
     */
    @Nullable
    String getActual();
}