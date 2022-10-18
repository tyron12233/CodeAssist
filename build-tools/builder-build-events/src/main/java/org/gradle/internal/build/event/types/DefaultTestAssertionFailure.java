package org.gradle.internal.build.event.types;

import org.gradle.tooling.internal.protocol.InternalFailure;
import org.gradle.tooling.internal.protocol.InternalTestAssertionFailure;

import java.util.Collections;
import java.util.List;

public class DefaultTestAssertionFailure extends AbstractTestFailure implements InternalTestAssertionFailure {

    private final String expected;
    private final String actual;

    private DefaultTestAssertionFailure(String message, String description, List<? extends InternalFailure> causes, String expected, String actual, String className, String stacktrace) {
        super(message, description, causes, className, stacktrace);
        this.expected = expected;
        this.actual = actual;
    }

    @Override
    public String getExpected() {
        return expected;
    }

    @Override
    public String getActual() {
        return actual;
    }

    public static DefaultTestAssertionFailure create(Throwable t, String message, String className, String stacktrace, String expected, String actual, List<InternalFailure> causes) {
        List<InternalFailure> causeFailure;
        if (causes.isEmpty()) {
            Throwable cause = t.getCause();
            causeFailure = cause != null && cause != t ? Collections.singletonList(DefaultFailure.fromThrowable(cause)) : Collections.emptyList();
        } else {
            causeFailure = causes;
        }
        return new DefaultTestAssertionFailure(message, stacktrace, causeFailure, expected, actual, className, stacktrace);
    }
}