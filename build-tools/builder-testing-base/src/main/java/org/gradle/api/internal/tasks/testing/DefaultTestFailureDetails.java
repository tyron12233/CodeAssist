package org.gradle.api.internal.tasks.testing;

import org.gradle.api.tasks.testing.TestFailureDetails;

import java.util.Objects;

public class DefaultTestFailureDetails implements TestFailureDetails {

    private final String message;
    private final String className;
    private final String stacktrace;
    private final boolean isAssertionFailure;
    private final String expected;
    private final String actual;

    public DefaultTestFailureDetails(String message, String className, String stacktrace, boolean isAssertionFailure, String expected, String actual) {
        this.message = message;
        this.className = className;
        this.stacktrace = stacktrace;
        this.isAssertionFailure = isAssertionFailure;
        this.expected = expected;
        this.actual = actual;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public String getStacktrace() {
        return stacktrace;
    }

    @Override
    public boolean isAssertionFailure() {
        return isAssertionFailure;
    }

    @Override
    public String getExpected() {
        return expected;
    }

    @Override
    public String getActual() {
        return actual;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultTestFailureDetails that = (DefaultTestFailureDetails) o;

        if (isAssertionFailure != that.isAssertionFailure) {
            return false;
        }
        if (!Objects.equals(message, that.message)) {
            return false;
        }
        if (!Objects.equals(className, that.className)) {
            return false;
        }
        if (!Objects.equals(stacktrace, that.stacktrace)) {
            return false;
        }
        if (!Objects.equals(expected, that.expected)) {
            return false;
        }
        return Objects.equals(actual, that.actual);
    }

    @Override
    public int hashCode() {
        int result = message != null ? message.hashCode() : 0;
        result = 31 * result + (className != null ? className.hashCode() : 0);
        result = 31 * result + (stacktrace != null ? stacktrace.hashCode() : 0);
        result = 31 * result + (isAssertionFailure ? 1 : 0);
        result = 31 * result + (expected != null ? expected.hashCode() : 0);
        result = 31 * result + (actual != null ? actual.hashCode() : 0);
        return result;
    }
}